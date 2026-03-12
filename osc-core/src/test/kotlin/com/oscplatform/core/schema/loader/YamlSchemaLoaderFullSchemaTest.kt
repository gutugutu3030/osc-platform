package com.oscplatform.core.schema.loader

import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * トップレベルの full-example.yaml（全機能を網羅したリアルなスキーマ）を [YamlSchemaLoader] で読み込み、期待どおりにパースされることを検証する結合テスト。
 *
 * インライン YAML のユニットテストでは カバーしにくい「7 メッセージ＋バンドル」規模の リグレッション検出を目的としている。
 */
class YamlSchemaLoaderFullSchemaTest {

  private val schema by lazy {
    val resource =
        checkNotNull(
            javaClass.classLoader.getResource("schemas/full-example.yaml"),
        ) {
          "テストリソース schemas/full-example.yaml が見つかりません"
        }
    YamlSchemaLoader().load(Paths.get(resource.toURI()))
  }

  // -------------------------------------------------------------------------
  // メッセージ総数・パス一覧
  // -------------------------------------------------------------------------

  @Test
  fun schemaContainsSevenMessages() {
    assertEquals(7, schema.messages.size)
  }

  @Test
  fun allExpectedPathsExist() {
    val paths = schema.messages.map { it.path }
    val expected =
        listOf(
            "/light/color",
            "/mesh/points",
            "/transform/matrix",
            "/audio/levels",
            "/scene/objects",
            "/device/info",
            "/data/chunk",
        )
    assertEquals(expected, paths)
  }

  // -------------------------------------------------------------------------
  // /light/color — 基本スカラー（INT × 3）
  // -------------------------------------------------------------------------

  @Test
  fun lightColorParsesThreeIntScalars() {
    val spec = assertNotNull(schema.findByPath("/light/color"))
    assertEquals(3, spec.args.size)
    spec.args.forEach { arg ->
      val scalar = assertIs<ScalarArgNode>(arg)
      assertEquals(OscType.INT, scalar.type)
      assertEquals(ScalarRole.VALUE, scalar.role)
    }
    assertEquals(listOf("r", "g", "b"), spec.args.map { it.name })
  }

  // -------------------------------------------------------------------------
  // /mesh/points — 動的長タプル配列（INT × 2 + FLOAT）
  // -------------------------------------------------------------------------

  @Test
  fun meshPointsParsesLengthScalarAndDynamicTupleArray() {
    val spec = assertNotNull(schema.findByPath("/mesh/points"))

    val countArg = assertIs<ScalarArgNode>(spec.args[0])
    assertEquals("pointCount", countArg.name)
    assertEquals(OscType.INT, countArg.type)
    assertEquals(ScalarRole.LENGTH, countArg.role)

    val pointsArg = assertIs<ArrayArgNode>(spec.args[1])
    assertEquals("points", pointsArg.name)
    assertEquals("pointCount", assertIs<LengthSpec.FromField>(pointsArg.length).fieldName)

    val tuple = assertIs<ArrayItemSpec.TupleItem>(pointsArg.item)
    assertEquals(listOf("x", "y", "z"), tuple.fields.map { it.name })
    assertEquals(listOf(OscType.INT, OscType.INT, OscType.FLOAT), tuple.fields.map { it.type })
  }

  // -------------------------------------------------------------------------
  // /transform/matrix — 固定長スカラー配列（FLOAT × 16）
  // -------------------------------------------------------------------------

  @Test
  fun transformMatrixParsesFixedLengthFloatArray() {
    val spec = assertNotNull(schema.findByPath("/transform/matrix"))

    val matrixArg = assertIs<ArrayArgNode>(spec.args[0])
    assertEquals("matrix", matrixArg.name)
    assertEquals(16, assertIs<LengthSpec.Fixed>(matrixArg.length).size)

    val item = assertIs<ArrayItemSpec.ScalarItem>(matrixArg.item)
    assertEquals(OscType.FLOAT, item.type)
  }

  // -------------------------------------------------------------------------
  // /audio/levels — 動的長スカラー配列（FLOAT）
  // -------------------------------------------------------------------------

  @Test
  fun audioLevelsParsesLengthScalarAndDynamicScalarArray() {
    val spec = assertNotNull(schema.findByPath("/audio/levels"))

    val countArg = assertIs<ScalarArgNode>(spec.args[0])
    assertEquals("channelCount", countArg.name)
    assertEquals(ScalarRole.LENGTH, countArg.role)

    val levelsArg = assertIs<ArrayArgNode>(spec.args[1])
    assertEquals("levels", levelsArg.name)
    assertEquals("channelCount", assertIs<LengthSpec.FromField>(levelsArg.length).fieldName)

    val item = assertIs<ArrayItemSpec.ScalarItem>(levelsArg.item)
    assertEquals(OscType.FLOAT, item.type)
  }

  // -------------------------------------------------------------------------
  // /scene/objects — 動的長タプル配列（INT + STRING + BOOL 混在）
  // -------------------------------------------------------------------------

  @Test
  fun sceneObjectsParsesHeterogeneousTupleFields() {
    val spec = assertNotNull(schema.findByPath("/scene/objects"))

    val objectsArg = assertIs<ArrayArgNode>(spec.args[1])
    val tuple = assertIs<ArrayItemSpec.TupleItem>(objectsArg.item)

    assertEquals(listOf("id", "label", "visible"), tuple.fields.map { it.name })
    assertEquals(
        listOf(OscType.INT, OscType.STRING, OscType.BOOL),
        tuple.fields.map { it.type },
    )
  }

  // -------------------------------------------------------------------------
  // /device/info — STRING と BOOL のスカラー
  // -------------------------------------------------------------------------

  @Test
  fun deviceInfoParsesStringAndBoolScalars() {
    val spec = assertNotNull(schema.findByPath("/device/info"))
    assertEquals(3, spec.args.size)

    val deviceId = assertIs<ScalarArgNode>(spec.args[0])
    assertEquals(OscType.STRING, deviceId.type)

    val connected = assertIs<ScalarArgNode>(spec.args[1])
    assertEquals(OscType.BOOL, connected.type)

    val firmware = assertIs<ScalarArgNode>(spec.args[2])
    assertEquals(OscType.STRING, firmware.type)
  }

  // -------------------------------------------------------------------------
  // /data/chunk — BLOB 型スカラー
  // -------------------------------------------------------------------------

  @Test
  fun dataChunkParsesBlobScalar() {
    val spec = assertNotNull(schema.findByPath("/data/chunk"))

    val payload = assertIs<ScalarArgNode>(spec.args[1])
    assertEquals("payload", payload.name)
    assertEquals(OscType.BLOB, payload.type)
  }

  // -------------------------------------------------------------------------
  // バンドル
  // -------------------------------------------------------------------------

  @Test
  fun schemContainsTwoBundles() {
    assertEquals(2, schema.bundles.size)
  }

  @Test
  fun lightBundleReferencesLightColor() {
    val bundle = assertNotNull(schema.bundles.find { it.name == "LightBundle" })
    assertEquals(listOf("/light/color"), bundle.messageRefs)
  }

  @Test
  fun sceneBundleReferencesThreeMessages() {
    val bundle = assertNotNull(schema.bundles.find { it.name == "SceneBundle" })
    assertEquals(
        listOf("/mesh/points", "/transform/matrix", "/scene/objects"),
        bundle.messageRefs,
    )
  }
}
