package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.CommandRouter
import com.example.data.AssistantAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read app name string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("TANJIT AI", appName)
  }

  @Test
  fun `local Bengali commands route deterministically`() = runTest {
    val router = CommandRouter()

    assertTrue(router.routeCommand("ইউটিউব খোলো").action is AssistantAction.OpenYouTube)
    assertEquals(AssistantAction.OpenFacebook, router.routeCommand("ফেসবুক খোলো").action)
    assertEquals(
      AssistantAction.SetFlashlight(true),
      router.routeCommand("ফ্ল্যাশলাইট জ্বালাও").action
    )
    assertEquals(
      AssistantAction.SetFlashlight(false),
      router.routeCommand("টর্চ বন্ধ করো").action
    )
    assertEquals(
      AssistantAction.OpenCamera(frontFacing = false),
      router.routeCommand("ক্যামেরা খোলো").action
    )
    val web = router.routeCommand("prothomalo.com খোলো").action as AssistantAction.OpenWebsite
    assertEquals("prothomalo.com", web.url)

    val call = router.routeCommand("রহিম কে কল করো").action as AssistantAction.CallContact
    assertTrue(call.queryName.contains("রহিম"))
  }
}
