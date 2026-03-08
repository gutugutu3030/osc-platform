package com.example;

/**
 * Java エントリポイント。
 * Kotlin ラッパー {@link OscBlockingClient} を使って OscRuntime を操作する。
 */
public class App {
    public static void main(String[] args) {
        // OscBlockingClient は suspend 呼び出しを runBlocking で隠蔽しており、
        // Java から普通のメソッド呼び出しで使用できる。
        OscBlockingClient client = new OscBlockingClient("127.0.0.1", 19020);

        client.start();
        client.sendFlag(true);
        client.sendFlag(false);
        client.stop();

        System.out.println("[App] 正常終了");
    }
}
