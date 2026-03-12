oscSchema {
    message("/light/color") {
        description("RGB カラーを設定する")
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
    }

    message("/mesh/points") {
        description("XYZ 座標点群を設定する")
        scalar("pointCount", INT, role = LENGTH)
        array("points", lengthFrom = "pointCount") {
            tuple {
                field("x", INT)
                field("y", INT)
                field("z", FLOAT)
            }
        }
    }

    message("/transform/matrix") {
        description("4x4 変換行列を設定する（列優先、16 要素）")
        array("matrix", length = 16) {
            scalar(FLOAT)
        }
    }

    message("/audio/levels") {
        description("チャンネルごとの音量レベルを設定する（0.0〜1.0）")
        scalar("channelCount", INT, role = LENGTH)
        array("levels", lengthFrom = "channelCount") {
            scalar(FLOAT)
        }
    }

    message("/scene/objects") {
        description("ラベルと表示状態を持つシーンオブジェクト一覧を設定する")
        scalar("objectCount", INT, role = LENGTH)
        array("objects", lengthFrom = "objectCount") {
            tuple {
                field("id", INT)
                field("label", STRING)
                field("visible", BOOL)
            }
        }
    }

    message("/device/info") {
        description("デバイスのメタ情報（ID・接続状態・ファームウェアバージョン）")
        scalar("deviceId", STRING)
        scalar("connected", BOOL)
        scalar("firmwareVersion", STRING)
    }

    message("/data/chunk") {
        description("インデックス付きのバイナリデータチャンクを送信する")
        scalar("chunkIndex", INT)
        scalar("payload", BLOB)
    }

    bundle("LightBundle") {
        description("照明関連メッセージのバンドル")
        message("/light/color")
    }

    bundle("SceneBundle") {
        description("3D シーン管理メッセージのバンドル")
        message("/mesh/points")
        message("/transform/matrix")
        message("/scene/objects")
    }
}
