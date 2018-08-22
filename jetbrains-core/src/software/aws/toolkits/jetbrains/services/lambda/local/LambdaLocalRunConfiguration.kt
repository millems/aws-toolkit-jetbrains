package software.aws.toolkits.jetbrains.services.lambda.local

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Ref
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.textCompletion.TextCompletionProvider
import com.intellij.util.xmlb.XmlSerializer
import com.jetbrains.extensions.getSdk
import icons.AwsIcons
import org.jdom.Element
import org.jetbrains.annotations.TestOnly
import software.amazon.awssdk.services.lambda.model.Runtime
import software.aws.toolkits.jetbrains.services.lambda.*
import software.aws.toolkits.jetbrains.services.lambda.Lambda.findPsiElementsForHandler
import software.aws.toolkits.jetbrains.utils.ui.populateValues
import software.aws.toolkits.resources.message
import javax.swing.JPanel

class LambdaRunConfigurationType :
        ConfigurationTypeBase("aws.lambda", message("lambda.service_name"), message("lambda.run_configuration.description"), AwsIcons.Logos.LAMBDA) {
    init {
        addFactory(LambdaLocalRunConfigurationFactory(this))
    }

    companion object {
        fun getInstance(): LambdaRunConfigurationType = ConfigurationTypeUtil.findConfigurationType(LambdaRunConfigurationType::class.java)
    }
}

class LambdaLocalRunConfigurationFactory(configuration: LambdaRunConfigurationType) : ConfigurationFactory(configuration) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration = LambdaLocalRunConfiguration(project, this)
}

class LambdaLocalRunConfiguration(project: Project, factory: ConfigurationFactory) : LocatableConfigurationBase(project, factory, "AWS Lambda"),
        RunProfileWithCompileBeforeLaunchOption {

    internal var settings = PersistableLambdaRunSettings()

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = LocalLambdaRunSettingsEditor(project)

    override fun checkConfiguration() {
        settings.validateAndCreateImmutable(project)
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val settings = try {
            settings.validateAndCreateImmutable(project)
        } catch (e: Exception) {
            throw ExecutionException(e.message)
        }
        val provider = settings.runtime.runtimeGroup?.let { LambdaLocalRunProvider.getInstance(it) }
            ?: throw ExecutionException("Unable to find run provider for ${settings.runtime}")
        return provider.createRunProfileState(environment, project, settings)
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        XmlSerializer.serializeInto(settings, element)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        XmlSerializer.deserializeInto(settings, element)
    }

    override fun suggestedName(): String? = settings.handler

    @TestOnly
    fun configure(runtime: Runtime?, handler: String, input: String? = null, envVars: MutableMap<String, String> = mutableMapOf()) {
        settings.input = input
        settings.runtime = runtime?.name
        settings.handler = handler
        settings.environmentVariables = envVars
    }

    internal data class PersistableLambdaRunSettings(
        var runtime: String? = null,
        var handler: String? = null,
        var input: String? = null,
        var environmentVariables: MutableMap<String, String> = mutableMapOf()
    ) {
        fun validateAndCreateImmutable(project: Project): LambdaRunSettings {
            val handler = handler ?: throw RuntimeConfigurationError(message("lambda.run_configuration.no_handler_specified"))
            val runtime = runtime?.let { Runtime.valueOf(it) } ?: throw RuntimeConfigurationError(message("lambda.run_configuration.no_runtime_specified"))
            val element = findPsiElementsForHandler(project, runtime, handler).firstOrNull()
                ?: throw RuntimeConfigurationError(message("lambda.run_configuration.handler_not_found", handler))
            return LambdaRunSettings(runtime, handler, input, environmentVariables, element)
        }
    }
}

class LocalLambdaRunSettingsEditor(project: Project) : SettingsEditor<LambdaLocalRunConfiguration>() {
    private val view = LocalLambdaRunSettingsEditorPanel(project, HandlerCompletionProvider(project))

    init {
        val supported = LambdaLocalRunProvider.supportedRuntimeGroups.flatMap { it.runtimes }.map { it }.sorted()
        val selected =
            ProjectRootManager.getInstance(project).projectSdk
                ?.let { RuntimeGroup.runtimeForSdk(it) }
                ?.let { if (it in supported) it else null }
        view.runtime.populateValues(selected = selected) { supported }
    }

    override fun resetEditorFrom(configuration: LambdaLocalRunConfiguration) {
        view.runtime.selectedItem = configuration.settings.runtime?.let { Runtime.valueOf(it) }
        view.handler.setText(configuration.settings.handler)
        view.input.setText(configuration.settings.input)
        view.environmentVariables.envVars = configuration.settings.environmentVariables
    }

    override fun createEditor(): JPanel = view.panel

    override fun applyEditorTo(configuration: LambdaLocalRunConfiguration) {
        configuration.settings.runtime = (view.runtime.selectedItem as? Runtime)?.name
        configuration.settings.handler = view.handler.text
        configuration.settings.input = view.input.text
        configuration.settings.environmentVariables = view.environmentVariables.envVars.toMutableMap()
    }
}

class HandlerCompletionProvider(private val project: Project) : TextCompletionProvider {
    override fun applyPrefixMatcher(result: CompletionResultSet, prefix: String): CompletionResultSet = result.withPrefixMatcher(PlainPrefixMatcher(prefix))

    override fun getAdvertisement(): String? = null

    override fun getPrefix(text: String, offset: Int): String? = text

    override fun fillCompletionVariants(parameters: CompletionParameters, prefix: String, result: CompletionResultSet) {
        FileBasedIndex.getInstance().getAllKeys(LambdaHandlerIndex.NAME, project).forEach { result.addElement(LookupElementBuilder.create(it)) }
        result.stopHere()
    }

    override fun acceptChar(c: Char): CharFilter.Result? {
        return if (c.isWhitespace()) {
            CharFilter.Result.SELECT_ITEM_AND_FINISH_LOOKUP
        } else {
            CharFilter.Result.ADD_TO_PREFIX
        }
    }
}

class LambdaRunSettings(
    val runtime: Runtime,
    val handler: String,
    val input: String?,
    val environmentVariables: Map<String, String>,
    val handlerElement: NavigatablePsiElement
)

interface LambdaLocalRunProvider {
    fun createRunProfileState(environment: ExecutionEnvironment, project: Project, settings: LambdaRunSettings): RunProfileState

    companion object : RuntimeGroupExtensionPointObject<LambdaLocalRunProvider>(ExtensionPointName.create("aws.toolkit.lambda.localRunProvider"))
}

class LambdaLocalRunConfigurationProducer : RunConfigurationProducer<LambdaLocalRunConfiguration>(LambdaRunConfigurationType.getInstance()) {

    override fun setupConfigurationFromContext(configuration: LambdaLocalRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val element = context.psiLocation ?: return false
        val runtimeGroup = element.language.runtimeGroup ?: return false
        if (runtimeGroup !in LambdaHandlerResolver.supportedRuntimeGroups) {
            return false
        }
        val resolver = LambdaHandlerResolver.getInstance(runtimeGroup)
        val handler = resolver.determineHandler(element) ?: return false
//        val runtime = null
        configuration.configure(null, handler)
        return true
    }

    override fun isConfigurationFromContext(configuration: LambdaLocalRunConfiguration, context: ConfigurationContext): Boolean {
        val element = context.psiLocation ?: return false
        val runtimeGroup = element.language.runtimeGroup ?: return false
        if (runtimeGroup !in LambdaHandlerResolver.supportedRuntimeGroups) {
            return false
        }
        val resolver = LambdaHandlerResolver.getInstance(runtimeGroup)
        val handler = resolver.determineHandler(element) ?: return false
        val runtime = context.module.getSdk()?.let { RuntimeGroup.runtimeForSdk(it) }
        return configuration.settings.handler == handler && configuration.settings.runtime == runtime?.name
    }

}