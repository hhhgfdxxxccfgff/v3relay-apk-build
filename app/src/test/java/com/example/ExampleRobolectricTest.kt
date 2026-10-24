package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.relay.data.ConfigRepository
import com.example.relay.data.ProxyConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("V3 Relay Proxy", appName)
  }

  @Test
  fun `save and load proxy config`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = ConfigRepository(context)
    val testConfig = ProxyConfig(
      workerUrl = "https://test.workers.dev",
      secret = "secret123",
      cleanIp = "1.2.3.4",
      socksPort = 1080,
      httpPort = 8080,
      configCode = "mo://test",
      configName = "Test Server"
    )
    repo.saveConfig(testConfig)
    val loaded = repo.loadConfig()
    assertEquals(testConfig.workerUrl, loaded.workerUrl)
    assertEquals(testConfig.secret, loaded.secret)
    assertEquals(testConfig.cleanIp, loaded.cleanIp)
    assertEquals(testConfig.socksPort, loaded.socksPort)
    assertEquals(testConfig.httpPort, loaded.httpPort)
    assertEquals(testConfig.mode, loaded.mode)
    assertEquals(testConfig.configCode, loaded.configCode)
    assertEquals(testConfig.configName, loaded.configName)
  }
}
