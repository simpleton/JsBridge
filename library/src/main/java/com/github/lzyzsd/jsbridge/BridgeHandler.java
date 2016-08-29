package com.github.lzyzsd.jsbridge;

import android.webkit.ValueCallback;

public interface BridgeHandler {
    void handler(String data, ValueCallback<String> function);
}
