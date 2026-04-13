package com.justb81.watchbuddy.phone.ui.onboarding

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.justb81.watchbuddy.R

@Composable
fun OnboardingScreen(
    onSuccess: () -> Unit,
    onSkip: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state) {
        if (state is OnboardingState.Success) onSuccess()
    }

    LaunchedEffect(Unit) {
        viewModel.requestDeviceCode()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            // Logo / Title
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn() togetherWith fadeOut() }
            ) { currentState ->
                when (currentState) {
                    is OnboardingState.LoadingCode -> {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }

                    is OnboardingState.WaitingForPin -> {
                        PinCard(
                            userCode        = currentState.userCode,
                            verificationUrl = currentState.verificationUrl,
                            expiresIn       = currentState.expiresInSeconds
                        )
                    }

                    is OnboardingState.Polling -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.onboarding_waiting),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    is OnboardingState.Error -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text  = currentState.message,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { viewModel.requestDeviceCode() }) {
                                Text(stringResource(R.string.onboarding_retry))
                            }
                        }
                    }

                    is OnboardingState.NotConfigured -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.onboarding_not_configured),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    else -> {}
                }
            }

            TextButton(onClick = onSkip) {
                Text(
                    stringResource(R.string.onboarding_skip),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun PinCard(userCode: String, verificationUrl: String, expiresIn: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Step 1
        Text(
            text  = stringResource(R.string.onboarding_step1),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text       = stringResource(R.string.onboarding_activate_url),
            style      = MaterialTheme.typography.titleMedium,
            color      = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        // Step 2
        Text(
            text  = stringResource(R.string.onboarding_step2),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        // PIN Box
        Box(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                )
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 32.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text       = userCode,
                fontSize   = 36.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                color      = MaterialTheme.colorScheme.onSurface,
                letterSpacing = 8.sp
            )
        }

        // Countdown
        LinearProgressIndicator(
            progress    = { expiresIn / 600f },
            modifier    = Modifier.fillMaxWidth(0.6f),
            color       = if (expiresIn > 60) MaterialTheme.colorScheme.primary
                          else MaterialTheme.colorScheme.error,
            trackColor  = MaterialTheme.colorScheme.surfaceVariant
        )
        Text(
            text  = stringResource(R.string.onboarding_code_expires, expiresIn),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )

        // Waiting indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text  = stringResource(R.string.onboarding_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}
