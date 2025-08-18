package org.cc.tool

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import java.io.IOException

class ClassSourceToolset : McpToolset {
    val logger by lazy { logger<ClassSourceToolset>() }

    @McpTool
    @McpDescription(
        """
Searches for class files by class name (supports short class names) and returns the source code. This function supports both local project classes and third-party library classes. For third-party classes without source code, it automatically uses decompilation to retrieve the source code.
When multiple classes with the same name are encountered, the fully qualified names of all matching classes are returned. To retrieve the source code, call the class_source function again to specify the class name.
By default, the first 500 lines of the source file are returned. You can specify a lineOffset and lineLimit to retrieve a specific line.

通过类名(支持短类名)查找类文件并返回源码信息。支持项目本地类和第三方库类，对于没有源码的第三方类会自动使用反编译功能获取源码。
当遇到多个同名类时，回返回所有匹配的类的全限定名，需要通过再次调用class_source指定权限定类名来获取源码
默认返回源码文件前500行，可以通过指定lineOffset和lineLimit查询指定行
"""
    )
    suspend fun class_source(
        @McpDescription("类名(支持短类名)") className: String,
        @McpDescription("行号偏移(默认0)") lineOffset: Int = 0,
        @McpDescription("查询行数(默认500)") lineLimit: Int = 500,
    ): Response {
        val project = currentCoroutineContext().project
        return runReadAction {
            try {
                if (className.isEmpty()) {
                    return@runReadAction Response.error("类名不能为空")
                }
                // 尝试查找\
                val psiClass = findClassByName(project, className)
                when {
                    psiClass.isEmpty() -> return@runReadAction Response.error("未找到类: $className")
                    psiClass.size == 1 -> {
                        // 获取源码
                        val sourceCode = getClassSourceCode(psiClass.first(), project, lineOffset, lineLimit)
                        return@runReadAction if (sourceCode == null) {
                            Response.error("无法获取类 $className 的源码")
                        } else {
                            Response.success(
                                "找到类源码", ClassSourceResponse(
                                    file = psiClass.first().containingFile.virtualFile.path,
                                    sourceCode = sourceCode
                                )
                            )
                        }
                    }

                    else -> return@runReadAction Response.success(
                        "找到多个同名类",
                        ClassSourceResponse(sameNameClass = psiClass.map { it.qualifiedName })
                    )
                }
            } catch (e: Exception) {
                logger.error("获取类源码时发生错误", e)
                return@runReadAction Response.error("获取类源码时发生错误: ${e.message}")
            }
        }
    }

    private fun findClassByName(project: Project, className: String): List<PsiClass> {
        // 首先尝试通过完整类名查找
        val psiClass = JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project))
        return if (psiClass == null) {
            // 如果没找到，尝试通过短类名查找
            val shortNamesCache = PsiShortNamesCache.getInstance(project)
            val classes = shortNamesCache.getClassesByName(className, GlobalSearchScope.allScope(project))
            if (classes.isNotEmpty()) {
                // 如果有多个同名类，返回List
                classes.toList()
            } else {
                emptyList()
            }
        } else {
            listOf(psiClass)
        }
    }


    private fun getClassSourceCode(psiClass: PsiClass, project: Project, lineOffset: Int, lineLimit: Int): String? {
        try {
            if (isProjectClass(psiClass, project)) {
                return getSourceCodeWithLimit(psiClass, lineOffset, lineLimit)
            } else {
                return getLibraryClassSource(psiClass, project, lineOffset, lineLimit)
            }
        } catch (e: Exception) {
            logger.warn("获取源码失败，尝试反编译", e)
            // 如果获取源码失败，尝试反编译
            return getDecompiledSourceCode(psiClass, lineOffset, lineLimit)
        }
    }

    // 判断是否是项目中的类
    private fun isProjectClass(psiClass: PsiClass, project: Project): Boolean {
        val file = psiClass.getContainingFile().getVirtualFile()
        return ProjectFileIndex.getInstance(project).isInSourceContent(file)
    }

    private fun getSourceCodeWithLimit(psiClass: PsiClass, lineOffset: Int, lineLimit: Int): String {
        val containingFile = psiClass.containingFile
        val sourceCode = if (containingFile is PsiJavaFile) {
            // 如果是Java文件，返回完整的文件内容（包含包声明和import）
            containingFile.text
        } else {
            // 其他情况返回类的内容
            psiClass.text
        }

        return applyLineLimits(sourceCode, lineOffset, lineLimit)
    }

    private fun getLibraryClassSource(psiClass: PsiClass, project: Project, lineOffset: Int, lineLimit: Int): String? {
        // 尝试获取源码
        val sourceCode: String? = getLibrarySourceCode(psiClass, project, lineOffset, lineLimit)
        if (sourceCode != null) {
            return sourceCode
        }
        // 无源码则反编译
        return getDecompiledSourceCode(psiClass, lineOffset, lineLimit)
    }

    private fun getLibrarySourceCode(psiClass: PsiClass, project: Project, lineOffset: Int, lineLimit: Int): String? {
        try {
            val classFile = psiClass.containingFile.virtualFile
            for (entry in ProjectFileIndex.getInstance(project).getOrderEntriesForFile(classFile)) {
                if (entry is LibraryOrderEntry) {
                    // 检查源码附件
                    val sourceRoots = entry.getFiles(OrderRootType.SOURCES)
                    if (sourceRoots.size == 0) {
                        return null // 无源码附件
                    }

                    // 转换类名为文件路径
                    val sourcePath: String = qualifiedNameToPath(psiClass.qualifiedName)
                    for (sourceRoot in sourceRoots) {
                        val sourceFile = sourceRoot.findFileByRelativePath(sourcePath)
                        if (sourceFile != null) {
                            val fullSourceCode = String(sourceFile.contentsToByteArray())
                            return applyLineLimits(fullSourceCode, lineOffset, lineLimit)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            logger.error("Error reading source file", e)
        }
        return null
    }

    private fun qualifiedNameToPath(qualifiedName: String?): String {
        if (qualifiedName == null) return ""
        return qualifiedName.replace('.', '/') + ".java"
    }

    private fun getDecompiledSourceCode(psiClass: PsiClass, lineOffset: Int, lineLimit: Int): String? {
        try {
            // 尝试获取反编译的源码
            // IDEA会自动处理反编译，我们只需要获取PSI树的内容
            val containingFile = psiClass.containingFile

            // 对于编译后的类文件，IDEA会自动反编译并创建PSI树
            val sourceText = containingFile.text

            return applyLineLimits(sourceText, lineOffset, lineLimit)
        } catch (e: Exception) {
            logger.warn("反编译失败", e)
            return "反编译失败"
        }
    }

    private fun applyLineLimits(sourceText: String, lineOffset: Int, lineLimit: Int): String {
        val lines = sourceText.lines()
        val totalLines = lines.size

        // 验证参数
        val validLineOffset = if (lineOffset < 0) 0 else lineOffset
        val validLineLimit = if (lineLimit <= 0) 500 else lineLimit

        return when {
            validLineOffset >= totalLines -> {
                "// 指定的行偏移量超出文件范围，文件总共有 $totalLines 行"
            }

            else -> {
                val endIndex = minOf(validLineOffset + validLineLimit, totalLines)
                val selectedLines = lines.subList(validLineOffset, endIndex)
                val result = selectedLines.joinToString("\n")

                if (endIndex < totalLines) {
                    "$result\n\n// ... 源码过长，仅显示第 ${validLineOffset + 1}-$endIndex 行，共 $totalLines 行 ..."
                } else {
                    result
                }
            }
        }
    }
}

@Serializable
data class ClassSourceResponse(
    val file: String? = null,
    val sourceCode: String? = null,
    val sameNameClass: List<String?>? = null,
)

@Serializable
data class Response(val status: String, val message: String, val data: ClassSourceResponse?) {
    companion object {
        fun error(message: String): Response {
            return Response("error", message, null)
        }

        fun success(message: String, data: ClassSourceResponse): Response {
            return Response("success", message, data)
        }
    }
}