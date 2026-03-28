package com.oscplatform.adapter.webui

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [SchemaEditorServer] の静的アセット配信と HTML シェルを検証するテスト。 */
class SchemaEditorServerAssetRouteTest {

  /** エディタの HTML シェルが外部 CSS/JS を参照していることを確認する。 */
  @Test
  fun editorHtmlReferencesExternalAssets() = testApplication {
    val server = SchemaEditorServer()
    application { server.configureApplication(this) }

    val response = client.get("/")
    val body = response.bodyAsText()

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(body.contains("/assets/editor/editor.css"))
    assertTrue(body.contains("/assets/editor/editor.js"))
    assertTrue(body.contains("type=\"module\""))
    assertTrue(body.contains("括弧ペア補完"))
    assertFalse(body.contains("var debounceTimer = null;"))
  }

  /** エディタ用 JavaScript アセットが Ktor から配信されることを確認する。 */
  @Test
  fun editorJavascriptAssetIsServed() = testApplication {
    val server = SchemaEditorServer()
    application { server.configureApplication(this) }

    val response = client.get("/assets/editor/editor.js")
    val body = response.bodyAsText()

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.headers["Content-Type"].orEmpty().contains("javascript"))
    assertTrue(body.contains("/api/evaluate"))
    assertTrue(body.contains("SchemaPreviewController"))
  }

  /** 存在しないアセットは 404 になることを確認する。 */
  @Test
  fun missingAssetReturns404() = testApplication {
    val server = SchemaEditorServer()
    application { server.configureApplication(this) }

    val response = client.get("/assets/editor/missing.js")

    assertEquals(HttpStatusCode.NotFound, response.status)
  }
}
