package com.cordova.plugins.sms;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.mms.ContentType;
import com.google.android.mms.InvalidHeaderValueException;
import com.google.android.mms.pdu_alt.CharacterSets;
import com.google.android.mms.pdu_alt.EncodedStringValue;
import com.google.android.mms.pdu_alt.PduBody;
import com.google.android.mms.pdu_alt.PduComposer;
import com.google.android.mms.pdu_alt.PduHeaders;
import com.google.android.mms.pdu_alt.PduPart;
import com.google.android.mms.pdu_alt.SendReq;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * Created by emanfu on 5/29/17.
 */

public class SmsSender {
    private static final String LOG_TAG = "SmsSender";
    private static final String INTENT_FILTER_SMS_SENT = "SMS_SENT";
    private static final int SEND_SMS_REQ_CODE = 0;
    private static final int THUMBNAIL_SIZE = 800;
    private static final String ACTION_MMS_SENT = "com.example.android.apis.os.MMS_SENT_ACTION";
    private static final long DEFAULT_EXPIRY_TIME = 7 * 24 * 60 * 60;
    private static final int DEFAULT_PRIORITY = PduHeaders.PRIORITY_NORMAL;
    private static final String TEXT_PART_FILENAME = "text_0.txt";
    private static final String sSmilText =
        "<smil>" +
            "<head>" +
                "<layout>" +
                    "<root-layout/>" +
                    "<region height=\"100%%\" id=\"Text\" left=\"0%%\" top=\"0%%\" width=\"100%%\"/>" +
                "</layout>" +
            "</head>" +
            "<body>" +
                "<par dur=\"8000ms\">" +
                    "<text src=\"%s\" region=\"Text\"/>" +
                "</par>" +
            "</body>" +
        "</smil>";

    private final Context context;
    private final Activity activity;
    private Random mRandom = new Random();

    public static interface SmsCallback {
        void succeed();
        void error(String errorMsg);
    }

    public void sendSmsJson(JSONArray args, SmsCallback callback) throws JSONException, IOException {
        //parsing arguments
        String separator = ";";
        if (android.os.Build.MANUFACTURER.equalsIgnoreCase("Samsung")) {
            // See http://stackoverflow.com/questions/18974898/send-sms-through-intent-to-multiple-phone-numbers/18975676#18975676
            separator = ",";
        }
        String phoneNumber = args.getJSONArray(0).join(separator).replace("\"", "").replace(" ", "");
        String message = args.getString(1);
        String method = args.getString(2);
        boolean replaceLineBreaks = Boolean.parseBoolean(args.getString(3));

        List<String> imgUrls = null;
        JSONArray attachments = args.optJSONArray(4);
        if (attachments != null) {
            imgUrls = new LinkedList<String>();
            for (int i = 0; i < attachments.length(); i++) {
                imgUrls.add(attachments.getString(i));
            }
        }

        // replacing \n by new line if the parameter replaceLineBreaks is set to true
        if (replaceLineBreaks) {
            message = message.replace("\\n", System.getProperty("line.separator"));
        }

        if (!checkSupport()) {
            callback.error("SMS not supported on this platform");
            return;
        }

        if (method.equalsIgnoreCase("INTENT")) {
            invokeSMSIntent(phoneNumber, message);
            // always passes success back to the app in the intent case
            callback.succeed();
        } else if (imgUrls == null){
            sendSms(phoneNumber, message, callback);
        } else {
            sendMms(phoneNumber, message, imgUrls, callback);
        }
    }

    public SmsSender(Activity activity) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
    }

    @SuppressLint("NewApi")
    public void invokeSMSIntent(String phoneNumber, String message) {
        Intent sendIntent;
        if ("".equals(phoneNumber) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            String defaultSmsPackageName = Telephony.Sms.getDefaultSmsPackage(activity);

            sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("text/plain");
            sendIntent.putExtra(Intent.EXTRA_TEXT, message);

            if (defaultSmsPackageName != null) {
                sendIntent.setPackage(defaultSmsPackageName);
            }
        } else {
            sendIntent = new Intent(Intent.ACTION_VIEW);
            sendIntent.putExtra("sms_body", message);
            // See http://stackoverflow.com/questions/7242190/sending-sms-using-intent-does-not-add-recipients-on-some-devices
            sendIntent.putExtra("address", phoneNumber);
            sendIntent.setData(Uri.parse("smsto:" + Uri.encode(phoneNumber)));
        }
        activity.startActivity(sendIntent);
    }

    public void sendSms(String phoneNumber, String message, final SmsCallback callback) {
        // check if SMS is supported first
        if (!checkSupport()) {
            callback.error("SMS not supported on this platform");
            return;
        }

        SmsManager manager = SmsManager.getDefault();
        final ArrayList<String> parts = manager.divideMessage(message);

        // by creating this broadcast receiver we can check whether or not the SMS was sent
        final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {

            boolean anyError = false; //use to detect if one of the parts failed
            int partsCount = parts.size(); //number of parts to send

            @Override
            public void onReceive(Context context, Intent intent) {
                int resultCode = getResultCode();
                switch (resultCode) {
                    case SmsManager.STATUS_ON_ICC_SENT:
                    case Activity.RESULT_OK:
                        break;
                    case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                    case SmsManager.RESULT_ERROR_NO_SERVICE:
                    case SmsManager.RESULT_ERROR_NULL_PDU:
                    case SmsManager.RESULT_ERROR_RADIO_OFF:
                        anyError = true;
                        break;
                }
                // trigger the callback only when all the parts have been sent
                partsCount--;
                if (partsCount == 0) {
                    if (anyError) {
                        callback.error("Error: SMS status " + resultCode);
                    } else {
                        callback.succeed();
                    }
                    activity.unregisterReceiver(this);
                }
            }
        };

        // randomize the intent filter action to avoid using the same receiver
        String intentFilterAction = INTENT_FILTER_SMS_SENT + java.util.UUID.randomUUID().toString();
        activity.registerReceiver(broadcastReceiver, new IntentFilter(intentFilterAction));

        PendingIntent sentIntent = PendingIntent.getBroadcast(activity, 0,
                new Intent(intentFilterAction), 0);

        // depending on the number of parts we send a text message or multi parts
        if (parts.size() > 1) {
            ArrayList<PendingIntent> sentIntents = new ArrayList<PendingIntent>();
            for (int i = 0; i < parts.size(); i++) {
                sentIntents.add(sentIntent);
            }
            manager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, null);
        }
        else {
            manager.sendTextMessage(phoneNumber, null, message, sentIntent, null);
        }
    }

    public boolean checkSupport() {
        return activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    public void sendMms(String phoneNumber,
                        String message,
                        List<String> imgUrls,
                        final SmsCallback callback) throws IOException {

        // by creating this broadcast receiver we can check whether or not the SMS was sent
        final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            boolean anyError = false; //use to detect if one of the parts failed

            @Override
            public void onReceive(Context context, Intent intent) {
                int resultCode = getResultCode();
                switch (resultCode) {
                    case SmsManager.STATUS_ON_ICC_SENT:
                    case Activity.RESULT_OK:
                        break;
                    case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                    case SmsManager.RESULT_ERROR_NO_SERVICE:
                    case SmsManager.RESULT_ERROR_NULL_PDU:
                    case SmsManager.RESULT_ERROR_RADIO_OFF:
                        anyError = true;
                        break;
                }

                if (anyError) {
                    callback.error("Error: SMS status " + resultCode);
                } else {
                    callback.succeed();
                }
                activity.unregisterReceiver(this);
            }
        };

        // randomize the intent filter action to avoid using the same receiver
        String intentFilterAction = ACTION_MMS_SENT + java.util.UUID.randomUUID().toString();
        activity.registerReceiver(broadcastReceiver, new IntentFilter(intentFilterAction));

        final String fileName = "send." + String.valueOf(Math.abs(mRandom.nextLong())) + ".dat";
        File mSendFile = new File(context.getCacheDir(), fileName);
        final byte[] pdu = buildPdu(context, phoneNumber, "", message, imgUrls);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, new Intent(intentFilterAction), 0);

        final String appId = activity.getPackageName();
        Uri writerUri = (new Uri.Builder())
                .authority(appId + ".sms.MmsFileProvider")
                .path(fileName)
                .scheme(ContentResolver.SCHEME_CONTENT)
                .build();

        FileOutputStream writer = null;
        Uri contentUri = null;
        try {
            writer = new FileOutputStream(mSendFile);
            writer.write(pdu);
            contentUri = writerUri;
        } catch (final IOException e) {
            Log.e(LOG_TAG, "Error writing send file", e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                }
            }
        }

        if (contentUri != null) {
            SmsManager.getDefault().sendMultimediaMessage(context,
                    contentUri, null/*locationUrl*/, null/*configOverrides*/,
                    pendingIntent);
        } else {
            Log.e(LOG_TAG, "Error writing sending Mms");
            try {
                pendingIntent.send(SmsManager.MMS_ERROR_IO_ERROR);
            } catch (PendingIntent.CanceledException ex) {
                Log.e(LOG_TAG, "Mms pending intent cancelled?", ex);
            }
        }
    }

    private byte[] buildPdu(Context context,
                            String recipients,
                            String subject,
                            String text,
                            List<String> imgUrls) throws IOException {
        final SendReq req = new SendReq();
        // From, per spec
        final String lineNumber = getSimNumber(context);
        if (!TextUtils.isEmpty(lineNumber)) {
            req.setFrom(new EncodedStringValue(lineNumber));
        }
        // To
        EncodedStringValue[] encodedNumbers =
                EncodedStringValue.encodeStrings(recipients.split(" "));
        if (encodedNumbers != null) {
            req.setTo(encodedNumbers);
        }

        // Subject
        if (!TextUtils.isEmpty(subject)) {
            req.setSubject(new EncodedStringValue(subject));
        }

        // Date
        req.setDate(System.currentTimeMillis() / 1000);

        // Body
        PduBody body = new PduBody();

        // Add text part. Always add a smil part for compatibility, without it there
        // may be issues on some carriers/client apps
        int size = addTextPart(body, text, true/* add text smil */);

        if (imgUrls != null) {
            for (String imgUrl: imgUrls) {
                size += addImagePart(body, imgUrl);
            }
        }

        req.setBody(body);

        // Message size
        req.setMessageSize(size);

        // Message class
        req.setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.getBytes());

        // Expiry
        req.setExpiry(DEFAULT_EXPIRY_TIME);
        try {
            // Priority
            req.setPriority(DEFAULT_PRIORITY);
            // Delivery report
            req.setDeliveryReport(PduHeaders.VALUE_NO);
            // Read report
            req.setReadReport(PduHeaders.VALUE_NO);
        } catch (InvalidHeaderValueException e) {}
        return new PduComposer(context, req).make();
    }

    private static int addTextPart(PduBody pb, String message, boolean addTextSmil) {
        final PduPart part = new PduPart();
        // Set Charset if it's a text media.
        part.setCharset(CharacterSets.UTF_8);
        // Set Content-Type.
        part.setContentType(ContentType.TEXT_PLAIN.getBytes());
        // Set Content-Location.
        part.setContentLocation(TEXT_PART_FILENAME.getBytes());
        int index = TEXT_PART_FILENAME.lastIndexOf(".");
        String contentId = (index == -1) ? TEXT_PART_FILENAME
                : TEXT_PART_FILENAME.substring(0, index);
        part.setContentId(contentId.getBytes());
        part.setData(message.getBytes());
        pb.addPart(part);
        if (addTextSmil) {
            final String smil = String.format(sSmilText, TEXT_PART_FILENAME);
            addSmilPart(pb, smil);
        }
        return part.getData().length;
    }

    private static void addSmilPart(PduBody pb, String smil) {
        final PduPart smilPart = new PduPart();
        smilPart.setContentId("smil".getBytes());
        smilPart.setContentLocation("smil.xml".getBytes());
        smilPart.setContentType(ContentType.APP_SMIL.getBytes());
        smilPart.setData(smil.getBytes());
        pb.addPart(0, smilPart);
    }

    private int addImagePart(PduBody pb, String imgUrl) throws IOException {
        Uri imgUri = Uri.parse(imgUrl);
        Bitmap imgBitmap = getThumbnail(imgUri);
        byte[] imageBytes = bitmapToByteArray(imgBitmap);
        String imageName = "image_" + java.util.UUID.randomUUID().toString();

        final PduPart part = new PduPart();
        part.setContentType(ContentType.IMAGE_JPEG.getBytes());
        byte[] imageNameBytes = imageName.getBytes();
        part.setContentLocation(imageNameBytes);
        part.setContentId(imageNameBytes);
        part.setData(imageBytes);
        pb.addPart(part);
        return part.getData().length;
    }

    private byte[] bitmapToByteArray(Bitmap image) {
        if (image == null) {
            Log.v("Message", "image is null, returning byte array of size 0");
            return new byte[0];
        }

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.JPEG, 90, stream);
        return stream.toByteArray();
    }

    private static String getSimNumber(Context context) {
        final TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE);
        return telephonyManager.getLine1Number();
    }

    public Bitmap getThumbnail(Uri uri) throws IOException {
        ContentResolver contentResolver = context.getContentResolver();
        InputStream input = contentResolver.openInputStream(uri);

        BitmapFactory.Options onlyBoundsOptions = new BitmapFactory.Options();
        onlyBoundsOptions.inJustDecodeBounds = true;
        onlyBoundsOptions.inDither=true;//optional
        onlyBoundsOptions.inPreferredConfig=Bitmap.Config.ARGB_8888;//optional
        BitmapFactory.decodeStream(input, null, onlyBoundsOptions);
        input.close();

        if ((onlyBoundsOptions.outWidth == -1) || (onlyBoundsOptions.outHeight == -1)) {
            return null;
        }

        int originalSize = (onlyBoundsOptions.outHeight > onlyBoundsOptions.outWidth) ?
                onlyBoundsOptions.outHeight : onlyBoundsOptions.outWidth;

        double ratio = (originalSize > THUMBNAIL_SIZE) ? (originalSize / THUMBNAIL_SIZE) : 1.0;

        BitmapFactory.Options bitmapOptions = new BitmapFactory.Options();
        bitmapOptions.inSampleSize = (int)Math.floor(ratio); //getPowerOfTwoForSampleRatio(ratio);
        bitmapOptions.inDither = true; //optional
        bitmapOptions.inPreferredConfig= Bitmap.Config.ARGB_8888;//
        input = contentResolver.openInputStream(uri);
        Bitmap bitmap = BitmapFactory.decodeStream(input, null, bitmapOptions);
        input.close();
        return bitmap;
    }

    private static int getPowerOfTwoForSampleRatio(double ratio){
        int k = Integer.highestOneBit((int)Math.floor(ratio));
        if(k==0) return 1;
        else return k;
    }
}
