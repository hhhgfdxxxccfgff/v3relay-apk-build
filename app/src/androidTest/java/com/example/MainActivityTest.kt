package com.example

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun coreSectionsArePresent() {
        composeRule.onNodeWithTag("power_toggle_button").assertExists()
        composeRule.onNodeWithTag("mode_switch_container").assertExists()
        composeRule.onNodeWithTag("stats_card").assertExists()
        composeRule.onNodeWithTag("quick_setup_card").assertExists()
        composeRule.onNodeWithTag("config_section").assertExists()
        composeRule.onNodeWithTag("log_console").assertExists()
    }

    @Test
    fun modeSwitchHasBothOptions() {
        composeRule.onNodeWithTag("mode_vpn_button").assertExists()
        composeRule.onNodeWithTag("mode_proxy_button").assertExists()
    }

    @Test
    fun configInputsArePresent() {
        composeRule.onNodeWithTag("clean_ip_input").assertExists()
        composeRule.onNodeWithTag("config_code_input").assertExists()
        composeRule.onNodeWithTag("paste_config_button").assertExists()
    }
}
