package com.github.lzyzsd.jsbridge;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;

@SuppressLint("SetJavaScriptEnabled")
public class BridgeWebView extends WebView implements WebViewJavascriptBridge {

    @SuppressLint("Unused")
    private final String TAG = "BridgeWebView";

    Map<String, ValueCallback<String>> responseCallbacks = new HashMap<>();
    Map<String, BridgeHandler> messageHandlers = new HashMap<>();
    BridgeHandler defaultHandler = new DefaultHandler();

    private List<Message> startupMessage = new ArrayList<>();

    public List<Message> getStartupMessage() {
        return startupMessage;
    }

    public void setStartupMessage(List<Message> startupMessage) {
        this.startupMessage = startupMessage;
    }

    private long uniqueId = 0;

    public BridgeWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BridgeWebView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    public BridgeWebView(Context context) {
        super(context);
        init();
    }

    /**
     *
     * @param handler
     *            default handler,handle messages send by js without assigned handler name,
     *            if js message has handler name, it will be handled by named handlers registered by native
     */
    public void setDefaultHandler(BridgeHandler handler) {
       this.defaultHandler = handler;
    }

    private void init() {
        this.setVerticalScrollBarEnabled(false);
        this.setHorizontalScrollBarEnabled(false);
        this.getSettings().setJavaScriptEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        this.setWebViewClient(generateBridgeWebViewClient());
    }



    protected BridgeWebViewClient generateBridgeWebViewClient() {
        return new BridgeWebViewClient(this);
    }

    void handlerReturnData(String url) {

        String functionName = BridgeUtil.getFunctionFromReturnUrl(url);
        ValueCallback<String> f = responseCallbacks.get(functionName);
        String data = BridgeUtil.getDataFromReturnUrl(url);
        if (f != null) {
            f.onReceiveValue(data);
            responseCallbacks.remove(functionName);
        }
    }

    @Override
    public void send(String data) {
        send(data, null);
    }

    @Override
    public void send(String data, ValueCallback<String> responseCallback) {
        doSend(null, data, responseCallback);
    }

    private void doSend(String handlerName, String data, ValueCallback<String> responseCallback) {
        Message m = new Message();
        if (BridgeUtil.isPresent(data)) {
            m.setData(data);
        }
        if (BridgeUtil.isPresent(handlerName)) {
            m.setHandlerName(handlerName);
        }
        if (responseCallback != null) {
            String callbackStr = String.format(BridgeUtil.CALLBACK_ID_FORMAT, ++uniqueId + (BridgeUtil.UNDERLINE_STR + SystemClock.currentThreadTimeMillis()));
            responseCallbacks.put(callbackStr, responseCallback);
            m.setCallbackId(callbackStr);
        }
        queueMessage(m);
    }

    private void queueMessage(Message m) {
        if (startupMessage != null) {
            startupMessage.add(m);
        } else {
            dispatchMessage(m);
        }
    }

    void dispatchMessage(Message m) {
        String messageJson = m.toJson();
        String javascriptCommand;
        messageJson = BridgeUtil.escapeJsonString(messageJson);
        javascriptCommand = String.format(BridgeUtil.JS_HANDLE_MESSAGE_FROM_JAVA, messageJson);
        ValueCallback<String> callback = responseCallbacks.get(m.getCallbackId());
        if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
            this.loadUrl(javascriptCommand, callback);
        }
    }

    void flushMessageQueue() {
        if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
            loadUrl(BridgeUtil.JS_FETCH_QUEUE_FROM_JAVA, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String data) {
                    if (BridgeUtil.useEvaluateJS()) {
                        handleMessageGE19(data);
                    } else {
                        handleMessageLT19(data);
                    }
                }
            });
        }
    }

    private void handleMessageGE19(String url) {
        try {
            url = URLDecoder.decode(url, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return;
        }
        // remove duplicate quotation
        if (url.startsWith("\"") && url.endsWith("\"")) {
            url = url.substring(1, url.length()-1);
        }
        if (url.startsWith(BridgeUtil.YY_RETURN_DATA)) { // 如果是返回数据
            String data = BridgeUtil.getDataFromReturnUrl(url);
            handleMessageList(data);
        } else {
            Log.e(TAG, String.format("Error Happened %s", url));
        }
    }

    private void handleMessageLT19(String data) {
        handleMessageList(data);
    }

    private void handleMessageList(String data) {
        // deserializeMessage
        List<Message> msgList;
        try {
            msgList = Message.toArrayList(data);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        if (BridgeUtil.isBlank(msgList)) {
            return;
        }

        for (Message msg : msgList) {
            String responseId = msg.getResponseId();
            // 是否是response
            if (BridgeUtil.isPresent(responseId)) {
                ValueCallback<String> function = responseCallbacks.get(responseId);
                String responseData = msg.getResponseData();
                function.onReceiveValue(responseData);
                responseCallbacks.remove(responseId);
            } else {
                ValueCallback<String> responseFunction;
                // if had callbackId
                final String callbackId = msg.getCallbackId();
                if (BridgeUtil.isPresent(callbackId)) {
                    responseFunction = new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String data) {
                            Message responseMsg = new Message();
                            responseMsg.setResponseId(callbackId);
                            responseMsg.setResponseData(data);
                            queueMessage(responseMsg);
                        }
                    };
                } else {
                    responseFunction = new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String s) {

                        }
                    };
                }
                BridgeHandler handler;
                if (BridgeUtil.isPresent(msg.getHandlerName())) {
                    handler = messageHandlers.get(msg.getHandlerName());
                } else {
                    handler = defaultHandler;
                }
                if (handler != null){
                    handler.handler(msg.getData(), responseFunction);
                }
            }
        }
    }

    public void loadUrl(String jsUrl, final ValueCallback<String> callback) {
        Log.i(TAG, "loadUrl:" + jsUrl);
        evaluateJavascript(jsUrl, callback);
        if (!BridgeUtil.useEvaluateJS()) {
            responseCallbacks.put(BridgeUtil.parseFunctionName(jsUrl), callback);
        }
    }

    public void evaluateJavascript(String script, ValueCallback<String> callback) {
        if (BridgeUtil.isBlank(script)) {
            Log.e(TAG, "Script is Empty");
            return;
        }
        if (BridgeUtil.useEvaluateJS()) {
            super.evaluateJavascript(script, callback);
        } else {
            if (!reflectExecJS(this, script)) {
                try {
                    super.loadUrl(script);
                    //responseCallbacks.put(BridgeUtil.parseFunctionName(script), returnCallback);
                } catch (Exception e) {
                    Log.e(TAG, e.getMessage(), e);
                }
            }
        }
    }


    private static boolean reflectExecJS(WebView webView, String script) {
        final int JS_MAGIC_EXECUTE_JS = 194;
        try {
            Object mSysWebView = new Reflector<>(webView, "mSysWebView").get();
            Object mProvider = new Reflector<>(mSysWebView, "mProvider").get();
            Object mWebViewCore = new Reflector<>(mProvider, "mWebViewCore").get();
            Method sendMsgMethod = mWebViewCore.getClass().getDeclaredMethod("sendMessage", android.os.Message.class);
            sendMsgMethod.setAccessible(true);
            android.os.Message msg = android.os.Message.obtain(null, JS_MAGIC_EXECUTE_JS, script);
            sendMsgMethod.invoke(mWebViewCore, msg);
            return true;
        } catch (Exception e) {
            Log.e("reflectExecJS", e.getMessage(), e);
        }
        return false;
    }


    /**
     * register handler,so that javascript can call it
     */
    public void registerHandler(String handlerName, BridgeHandler handler) {
        if (handler != null) {
            messageHandlers.put(handlerName, handler);
        }
    }

    /**
     * call javascript registered handler
     */
    public void callHandler(String handlerName, String data, ValueCallback<String> callBack) {
        doSend(handlerName, data, callBack);
    }
}
