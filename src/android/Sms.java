package com.cordova.plugins.sms;

import android.content.pm.PackageManager;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

public class Sms extends CordovaPlugin {

    private final String ACTION_SEND_SMS = "send";
    private final String ACTION_HAS_PERMISSION = "has_permission";
    private static final int SEND_SMS_REQ_CODE = 0;

    private CallbackContext callbackContext;
    private JSONArray args;

    @Override
    public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;
        this.args = args;
        if (action.equals(ACTION_SEND_SMS)) {
            if (hasPermission()) {
                sendSMS();
            } else {
                requestPermission();
            }
            return true;
        }
        else if (action.equals(ACTION_HAS_PERMISSION)) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, hasPermission()));
            return true;
        }
        return false;
    }

    private boolean hasPermission() {
        return cordova.hasPermission(android.Manifest.permission.SEND_SMS);
    }

    private void requestPermission() {
        cordova.requestPermission(this, SEND_SMS_REQ_CODE, android.Manifest.permission.SEND_SMS);
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "User has denied permission"));
                return;
            }
        }
        sendSMS();
    }

    private boolean sendSMS() {
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    SmsSender smsSender = new SmsSender(cordova.getActivity());
                    smsSender.sendSmsJson(args, new SmsSender.SmsCallback() {
                        @Override
                        public void succeed() {
                            callbackContext.sendPluginResult(
                                    new PluginResult(PluginResult.Status.OK));
                        }

                        @Override
                        public void error(String errorMsg) {
                            callbackContext.sendPluginResult(
                                    new PluginResult(PluginResult.Status.ERROR, errorMsg));
                        }
                    });
                } catch (JSONException ex) {
                    callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.JSON_EXCEPTION));
                } catch (IOException ex) {
                    callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.IO_EXCEPTION));
                }
            }
        });
        return true;
    }
}
