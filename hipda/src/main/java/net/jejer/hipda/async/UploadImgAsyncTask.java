package net.jejer.hipda.async;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.util.Log;

import net.jejer.hipda.bean.HiSettingsHelper;
import net.jejer.hipda.utils.CursorUtils;
import net.jejer.hipda.utils.HiUtils;
import net.jejer.hipda.utils.ImageFileInfo;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class UploadImgAsyncTask extends AsyncTask<Uri, Integer, Void> {
    private final String LOG_TAG = getClass().getSimpleName();

    public final static int STAGE_UPLOADING = -1;
    public final static int MAX_QUALITY = 90;
    public final static int MAX_IMAGE_SIZE = 300 * 1024; // max file size 300K

    private static final int UPLOAD_CONNECT_TIMEOUT = 15 * 1000;
    private static final int UPLOAD_READ_TIMEOUT = 5 * 60 * 1000;

    private UploadImgListener mListener;

    private String mUid;
    private String mHash;
    private Context mCtx;

    private Uri mCurrentUri;
    private String mMessage = "";
    private Bitmap mThumb;
    private int mTotal;
    private int mCurrent;
    private String mCurrentFileName = "";

    public UploadImgAsyncTask(Context ctx, UploadImgListener v, String uid, String hash) {
        mCtx = ctx;
        mListener = v;
        mUid = uid;
        mHash = hash;
    }

    public interface UploadImgListener {
        void updateProgress(Uri uri, int total, int current, int percentage);

        void itemComplete(Uri uri, int total, int current, String currentFileName, String message, String imgId, Bitmap thumbtail);

        void complete();
    }

    @Override
    protected Void doInBackground(Uri... uris) {

        Map<String, String> post_param = new HashMap<String, String>();

        post_param.put("uid", mUid);
        post_param.put("hash", mHash);

        mTotal = uris.length;

        int i = 0;
        for (Uri uri : uris) {
            mCurrent = i++;
            String imgId = doUploadFile(HiUtils.UploadImgUrl, post_param, uri);
            mListener.itemComplete(uri, mTotal, mCurrent, mCurrentFileName, mMessage, imgId, mThumb);
        }

        return null;
    }

    @Override
    protected void onProgressUpdate(Integer... progress) {
        if (mListener != null) {
            mListener.updateProgress(mCurrentUri, mTotal, mCurrent, progress[0]);
        }
    }

    @Override
    protected void onPostExecute(Void result) {
        if (mListener != null) {
            mListener.complete();
        }
    }

    private static String getBoundry() {
        StringBuilder sb = new StringBuilder();
        for (int t = 1; t < 12; t++) {
            long time = System.currentTimeMillis() + t;
            if (time % 3 == 0) {
                sb.append((char) time % 9);
            } else if (time % 3 == 1) {
                sb.append((char) (65 + time % 26));
            } else {
                sb.append((char) (97 + time % 26));
            }
        }
        return sb.toString();
    }

    private String getBoundaryMessage(String boundary, Map<String, String> params, String fileField, String fileName, String fileType) {
        StringBuilder res = new StringBuilder("--").append(boundary).append("\r\n");

        for (String key : params.keySet()) {
            String value = params.get(key);
            res.append("Content-Disposition: form-data; name=\"")
                    .append(key).append("\"\r\n").append("\r\n")
                    .append(value).append("\r\n").append("--")
                    .append(boundary).append("\r\n");
        }
        res.append("Content-Disposition: form-data; name=\"").append(fileField)
                .append("\"; filename=\"").append(fileName)
                .append("\"\r\n").append("Content-Type: ")
                .append(fileType).append("\r\n\r\n");

        return res.toString();
    }

    public String doUploadFile(String urlStr, Map<String, String> param, Uri uri) {

        mCurrentUri = uri;
        mThumb = null;
        mMessage = "";
        mCurrentFileName = "";

        // update progress for start compress
        publishProgress(STAGE_UPLOADING);

        ImageFileInfo imageFileInfo = CursorUtils.getImageFileInfo(mCtx, uri);
        mCurrentFileName = imageFileInfo.getFileName();

        Bitmap bitmap;
        try {
            bitmap = MediaStore.Images.Media.getBitmap(mCtx.getContentResolver(), uri);
        } catch (Exception e) {
            Log.v(LOG_TAG, "Exception", e);
            mMessage = "图片压缩出现错误 " + e.getMessage();
            return null;
        }

        String fileType = "image/jpeg";
        String imageParamName = "Filedata";


        ByteArrayOutputStream baos = compressImage(imageFileInfo, bitmap);
        if (baos == null) {
            return null;
        }

        // update progress for start upload
        publishProgress(0);

        String BOUNDARYSTR = getBoundry();

        byte[] barry = null;
        int contentLength = 0;
        String sendStr = "";
        try {
            barry = ("--" + BOUNDARYSTR + "--\r\n").getBytes("UTF-8");
            SimpleDateFormat formatter = new SimpleDateFormat("yyMMdd_HHmm", Locale.US);
            String fileName = "IMG_" + formatter.format(new Date()) + ".jpg";
            sendStr = getBoundaryMessage(BOUNDARYSTR, param, imageParamName, fileName, fileType);
            contentLength = sendStr.getBytes("UTF-8").length + baos.size() + 2 * barry.length;
        } catch (UnsupportedEncodingException ignored) {

        }
        String lenstr = Integer.toString(contentLength);

        String imgId = "";
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        try {
            URL url = new URL(urlStr);

            urlConnection = (HttpURLConnection) url.openConnection();

            urlConnection.setRequestProperty("User-Agent", HiUtils.UserAgent);
            urlConnection.setRequestProperty("Cookie", "cdb_auth=" + HiSettingsHelper.getInstance().getCookieAuth());

            urlConnection.setConnectTimeout(UPLOAD_CONNECT_TIMEOUT);
            urlConnection.setReadTimeout(UPLOAD_READ_TIMEOUT);
            urlConnection.setDoInput(true);
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setUseCaches(false);
            urlConnection.setRequestProperty("Connection", "Keep-Alive");
            urlConnection.setRequestProperty("Content-type", "multipart/form-data;boundary=" + BOUNDARYSTR);
            urlConnection.setRequestProperty("Content-Length", lenstr);
            urlConnection.setFixedLengthStreamingMode(contentLength);
            urlConnection.connect();

            out = new BufferedOutputStream(urlConnection.getOutputStream());
            out.write(sendStr.getBytes("UTF-8"));

            int bytesLeft;
            int transferred = 0;
            int postSize;
            int maxPostSize = 1024;

            bytesLeft = baos.size();
            postSize = Math.min(bytesLeft, maxPostSize);
            final Thread thread = Thread.currentThread();
            while (bytesLeft > 0) {

                if (thread.isInterrupted()) {
                    throw new InterruptedIOException();
                }
                out.write(baos.toByteArray(), transferred, postSize);
                transferred += postSize;
                bytesLeft -= postSize;
                postSize = Math.min(bytesLeft, maxPostSize);
                if (transferred % 50 == 0)
                    out.flush();
                publishProgress((int) ((transferred * 100) / baos.size()));
            }

            //yes, write twice
            out.write(barry);
            out.write(barry);
            out.flush();
            int status = urlConnection.getResponseCode();

            if (status != HttpURLConnection.HTTP_OK) {
                mMessage = "上传错误代码 " + status;
                return null;
            }
            Log.v(LOG_TAG, "uploading image, response : " + urlConnection.getResponseCode() + ", " + urlConnection.getResponseMessage());
            InputStream in = urlConnection.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String inputLine = "";
            while ((inputLine = br.readLine()) != null) {
                imgId += inputLine;
            }

        } catch (Exception e) {
            Log.e(LOG_TAG, "Error uploading image : " + e.getMessage());
            mMessage = "上传发生网络错误 " + e.getMessage();
            return null;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {

                }
            }
            try {
                baos.close();
            } catch (IOException ignored) {

            }
            if (urlConnection != null)
                urlConnection.disconnect();
        }

        // DISCUZUPLOAD|0|1721652|1
        if (!imgId.startsWith("DISCUZUPLOAD")) {
            mMessage = "错误的图片ID : " + imgId;
            return null;
        } else {
            String[] s = imgId.split("\\|");
            if (s.length < 3 || s[2].equals("0")) {
                mMessage = "错误的图片ID : " + imgId;
                return null;
            } else {
                imgId = s[2];
            }
        }

        return imgId;
    }

    private ByteArrayOutputStream compressImage(ImageFileInfo imageFileInfo, Bitmap bitmap) {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(CompressFormat.JPEG, MAX_QUALITY, baos);

        int origionalSize = baos.size();
        if (origionalSize <= MAX_IMAGE_SIZE) {
            mThumb = ThumbnailUtils.extractThumbnail(bitmap, 48, 48);
            return baos;
        }

        //Check and avoid BitmapFactory.decodeStream FC
        if (baos.size() / 1024 > 1024) {
            baos.reset();
            bitmap.compress(CompressFormat.JPEG, 50, baos);
        }

        //ignore ide suggestion, and do not change following 5 lines, unless you know exactly what to do
        ByteArrayInputStream isBm = new ByteArrayInputStream(baos.toByteArray());
        BitmapFactory.Options newOpts = new BitmapFactory.Options();
        newOpts.inJustDecodeBounds = true;
        Bitmap newbitmap = BitmapFactory.decodeStream(isBm, null, newOpts);
        newOpts.inJustDecodeBounds = false;

        int w = newOpts.outWidth;
        int h = newOpts.outHeight;

        float hh = 720f;
        float ww = 720f;

        int be = 1;//be=1表示不缩放
        if (w > h && w > ww) {
            be = (int) (newOpts.outWidth / ww);
        } else if (w < h && h > hh) {
            be = (int) (newOpts.outHeight / hh);
        }
        if (be <= 0)
            be = 1;
        newOpts.inSampleSize = be;

        isBm = new ByteArrayInputStream(baos.toByteArray());
        newbitmap = BitmapFactory.decodeStream(isBm, null, newOpts);

        if (imageFileInfo.getOrientation() > 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(imageFileInfo.getOrientation());

            newbitmap = Bitmap.createBitmap(newbitmap, 0, 0, newbitmap.getWidth(),
                    newbitmap.getHeight(), matrix, true);
        }

        int quality = MAX_QUALITY;
        baos.reset();
        newbitmap.compress(CompressFormat.JPEG, quality, baos);
        while (baos.toByteArray().length > MAX_IMAGE_SIZE) {
            quality -= 10;
            baos.reset();
            newbitmap.compress(CompressFormat.JPEG, quality, baos);
        }

        mThumb = ThumbnailUtils.extractThumbnail(newbitmap, 128, 128);

        Log.v(LOG_TAG, "Image Compressed: " + quality + "%,  size: " + baos.size() + " bytes, original size: " + origionalSize + " bytes");
        return baos;
    }
}
