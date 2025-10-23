package org.cc.tool.tool

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.cc.tool.mcp.AbstractTool
import org.cc.tool.mcp.McpTool
import org.cc.tool.utils.parse
import org.jetbrains.ide.RestService
import java.io.IOException

object ClassSourceToolset : AbstractTool {
    private val logger = logger<ClassSourceToolset>()

    override fun toolInfo() = McpTool(
        name = "class_source",
        description = "根据类名搜索并返回 Java 类的源代码。支持短类名搜索，可以指定行偏移和行数限制。",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "className" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("类名(支持短类名)")
                            )
                        ),
                        "lineOffset" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("integer"),
                                "description" to JsonPrimitive("行号偏移(默认0)"),
                                "default" to JsonPrimitive(0)
                            )
                        ),
                        "lineLimit" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("integer"),
                                "description" to JsonPrimitive("查询行数(默认500)"),
                                "default" to JsonPrimitive(500)
                            )
                        )
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("className")))
            )
        )
    )

    override fun execute(arguments: JsonElement?): String {
        if (arguments == null) {
            return "无效的参数:className"
        } else {
            val (className, lineOffset, lineLimit) = arguments.parse<ClassSourceArgs>()
            return getClassSource(className, lineOffset, lineLimit)
        }
    }

    /**
     * 获取类源码
     */
    private fun getClassSource(className: String, lineOffset: Int = 0, lineLimit: Int = 500): String {
        return runReadAction {
            try {
                if (className.isEmpty()) {
                    return@runReadAction "类名不能为空"
                }
                // 获取当前项目
                val project = RestService.getLastFocusedOrOpenedProject() ?: return@runReadAction "未找到打开的项目"

                // 尝试查找类
                val psiClasses = findClassByName(project, className)
                when {
                    psiClasses.isEmpty() -> return@runReadAction "未找到类: $className"
                    psiClasses.size == 1 -> {
                        // 获取源码
                        val sourceCode = getClassSourceCode(psiClasses.first(), project, lineOffset, lineLimit)
                        return@runReadAction sourceCode ?: "无法获取类 $className 的源码"
                    }

                    else -> {
                        // 多个同名类，返回列表
                        val classNames = psiClasses.mapNotNull { it.qualifiedName }
                        return@runReadAction "找到多个同名类: ${classNames.joinToString(", ")}"
                    }
                }
            } catch (e: Exception) {
                logger.error("获取类源码时发生错误", e)
                return@runReadAction "获取类源码时发生错误: ${e.message}"
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
        val file = psiClass.containingFile.virtualFile
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
                    val sourceRoots = entry.getRootFiles(OrderRootType.SOURCES)
                    if (sourceRoots.isEmpty()) {
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

/**
 * 类源码工具的参数
 */
@Serializable
data class ClassSourceArgs(
    val className: String,
    val lineOffset: Int = 0,
    val lineLimit: Int = 500
)