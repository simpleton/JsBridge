package com.github.lzyzsd.jsbridge;

import android.webkit.ValueCallback;

public class DefaultHandler implements BridgeHandler{

    String TAG = "DefaultHandler";

    @Override
    public void handler(String data, ValueCallback<String> function) {
        if(function != null){
            function.onReceiveValue("DefaultHandler response data");
        }
    }
}
