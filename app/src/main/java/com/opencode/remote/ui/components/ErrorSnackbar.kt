package com.opencode.remote.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opencode.remote.ui.strings.AppLocale

@Composable
fun BoxScope.ErrorSnackbar(
    error: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = AppLocale.strings

    error?.let { message ->
        Snackbar(
            modifier = modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            action = {
                TextButton(onClick = onDismiss) {
                    Text(s.close, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(message)
            }
        }
        LaunchedEffect(error) {
            kotlinx.coroutines.delay(5000)
            onDismiss()
        }
    }
}
