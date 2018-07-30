package software.aws.toolkits.jetbrains.core.credentials

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeAndWaitIfNeed
import com.intellij.openapi.ui.Messages
import org.jetbrains.annotations.CalledInAwt
import software.aws.toolkits.core.credentials.EnvironmentVariableToolkitCredentialsProviderFactory
import software.aws.toolkits.core.credentials.ProfileToolkitCredentialsProviderFactory
import software.aws.toolkits.core.credentials.SystemPropertyToolkitCredentialsProviderFactory
import software.aws.toolkits.core.credentials.ToolkitCredentialsProviderFactory
import software.aws.toolkits.jetbrains.core.AwsSdkClient
import software.aws.toolkits.jetbrains.core.region.AwsRegionProvider
import software.aws.toolkits.resources.message

class ProfileCredentialProviderFactory : CredentialProviderFactory {
    override fun createToolkitCredentialProviderFactory(): ToolkitCredentialsProviderFactory {
        return ProfileToolkitCredentialsProviderFactory(
            AwsSdkClient.getInstance().sdkHttpClient,
            AwsRegionProvider.getInstance(),
            { profileName, mfaDevice ->
                invokeAndWaitIfNeed(ModalityState.any()) {
                    promptForMfa(profileName, mfaDevice)
                }
            })
    }

    @CalledInAwt
    private fun promptForMfa(profileName: String, mfaDevice: String): String {
        return Messages.showInputDialog(
            message("credentials.profile.mfa.message", mfaDevice),
            message("credentials.profile.mfa.title", profileName),
            Messages.getQuestionIcon()
        ) ?: throw IllegalStateException("MFA challenge is required")
    }
}

class EnvironmentCredentialProviderFactory : CredentialProviderFactory {
    override fun createToolkitCredentialProviderFactory(): ToolkitCredentialsProviderFactory {
        return EnvironmentVariableToolkitCredentialsProviderFactory()
    }
}

class SystemPropertyCredentialProviderFactory : CredentialProviderFactory {
    override fun createToolkitCredentialProviderFactory(): ToolkitCredentialsProviderFactory {
        return SystemPropertyToolkitCredentialsProviderFactory()
    }
}