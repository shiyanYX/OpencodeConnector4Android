package com.opencode.remote.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opencode.remote.data.api.dto.FileNode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RightPanelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `displays path breadcrumb`() {
        composeTestRule.setContent {
            RightPanel(
                files = emptyList(),
                currentPath = "/projects/my-app",
                onNavigateToDirectory = {},
                isLoadingFiles = false,
            )
        }
        composeTestRule.onNodeWithText("/projects/my-app").assertIsDisplayed()
    }

    @Test
    fun `displays file tree with files`() {
        val files = listOf(
            FileNode("src", "/src", "/abs/src", "directory"),
            FileNode("README.md", "/README.md", "/abs/README.md", "file"),
        )
        composeTestRule.setContent {
            RightPanel(
                files = files,
                currentPath = "/",
                onNavigateToDirectory = {},
                isLoadingFiles = false,
            )
        }
        composeTestRule.onNodeWithText("src").assertIsDisplayed()
        composeTestRule.onNodeWithText("README.md").assertIsDisplayed()
    }

    @Test
    fun `directory click in panel triggers navigation`() {
        val files = listOf(
            FileNode("src", "/src", "/abs/src", "directory"),
        )
        var navigatedTo = ""
        composeTestRule.setContent {
            RightPanel(
                files = files,
                currentPath = "/",
                onNavigateToDirectory = { navigatedTo = it },
                isLoadingFiles = false,
            )
        }
        composeTestRule.onNodeWithTag("file_item_src").performClick()
        assertTrue("/src" == navigatedTo)
    }
}
