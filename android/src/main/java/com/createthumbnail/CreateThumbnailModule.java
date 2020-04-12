package com.reactlibrary;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import org.apache.commons.io.comparator.LastModifiedFileComparator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;

public class CreateThumbnailModule extends ReactContextBaseJavaModule {

    private final ReactApplicationContext reactContext;
    private static final long CACHE_DIR_MAX_SIZE = 104857600L; // 100MB
    private Handler handler;

    public CreateThumbnailModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        HandlerThread thread = new HandlerThread("thumb");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public String getName() {
        return "CreateThumbnail";
    }

    @ReactMethod
    public void create(ReadableMap options, Promise promise) {
        String thumbnailDir = reactContext.getApplicationContext().getCacheDir().getAbsolutePath() + "/thumbnails";

        File thumbDir = new File(thumbnailDir);

        if (!thumbDir.exists()) {
            thumbDir.mkdirs();
        }

        long newSize = getDirSize(thumbDir);
        // free up some cached data if size of cache dir exceeds CACHE_DIR_MAX_SIZE
        if (newSize > CACHE_DIR_MAX_SIZE) {
            cleanDir(thumbDir, CACHE_DIR_MAX_SIZE / 2);
        }

        handler.post(new ThumbRunnable(this.reactContext,thumbnailDir, options, promise));

    }

    // delete previously added files one by one untill requred space is available
    private static void cleanDir(File dir, long bytes) {
        long bytesDeleted = 0;
        File[] files = dir.listFiles();
        Arrays.sort(files, LastModifiedFileComparator.LASTMODIFIED_COMPARATOR);

        for (File file : files) {
            bytesDeleted += file.length();
            file.delete();

            if (bytesDeleted >= bytes) {
                break;
            }
        }
    }

    private static long getDirSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();

        for (File file : files) {
            if (file.isFile()) {
                size += file.length();
            }
        }

        return size;
    }

    private static class ThumbRunnable implements Runnable {
        private String thumbnailDir;
        private ReadableMap options;
        private Promise promise;
        private MediaMetadataRetriever retriever;
        private WeakReference<ReactApplicationContext> mContext;

        public ThumbRunnable(ReactApplicationContext context, String thumbnailDir, ReadableMap options, Promise promise) {
            this.thumbnailDir = thumbnailDir;
            this.options = options;
            this.promise = promise;
            this.retriever = new MediaMetadataRetriever();
            this.mContext = new WeakReference<>(context);

        }

        @Override
        public void run() {
            String filePath = options.hasKey("url") ? options.getString("url") : "";
            String type = options.hasKey("type") ? options.getString("type") : "remote";
            String format = options.hasKey("format") ? options.getString("format") : "jpeg";
            int timeStamp = options.hasKey("timeStamp") ? options.getInt("timeStamp") : 1;
            String fileName = "thumb-" + md5(filePath) + "." + format;

            final WritableMap resultMap = Arguments.createMap();

            File file = new File(thumbnailDir, fileName);

            if (file.exists()) {
                BitmapFactory.Options options = new BitmapFactory.Options();

                options.inJustDecodeBounds = true;

                BitmapFactory.decodeFile(file.getPath(), options);

                resultMap.putString("path", "file://" + thumbnailDir + '/' + fileName);
                resultMap.putDouble("width", options.outWidth);
                resultMap.putDouble("height", options.outHeight);

                mContext.get().runOnJSQueueThread(new Runnable() {
                    @Override
                    public void run() {
                        promise.resolve(resultMap);
                    }
                });
                return;
            }

            OutputStream fOut = null;

            try {

                file.createNewFile();

                if (type.equals("local")) {
                    filePath = filePath.replace("file://", "");
                    retriever.setDataSource(filePath);
                } else {
                    if (VERSION.SDK_INT < 14) {
                        throw new IllegalStateException("remote videos aren't supported on sdk_version < 14");
                    }
                    retriever.setDataSource(filePath, new HashMap<String, String>());
                }

                Bitmap image = retriever.getFrameAtTime(timeStamp * 1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                retriever.release();

                fOut = new FileOutputStream(file);

                // 100 means no compression, the lower you go, the stronger the compression
                if (format == "png") {
                    image.compress(Bitmap.CompressFormat.PNG, 100, fOut);
                } else {
                    image.compress(Bitmap.CompressFormat.JPEG, 90, fOut);
                }

                fOut.flush();
                fOut.close();

                resultMap.putString("path", "file://" + thumbnailDir + '/' + fileName);
                resultMap.putDouble("width", image.getWidth());
                resultMap.putDouble("height", image.getHeight());


                mContext.get().runOnJSQueueThread(new Runnable() {
                    @Override
                    public void run() {
                        promise.resolve(resultMap);
                    }
                });
            } catch (final Exception e) {
                mContext.get().runOnJSQueueThread(new Runnable() {
                    @Override
                    public void run() {
                        promise.reject("CreateThumbnail_ERROR", e);
                    }
                });
            }

        }

        public static String md5(String string) {
            if (TextUtils.isEmpty(string)) {
                return "";
            }
            MessageDigest md5 = null;
            try {
                md5 = MessageDigest.getInstance("MD5");
                byte[] bytes = md5.digest(string.getBytes());
                String result = "";
                for (byte b : bytes) {
                    String temp = Integer.toHexString(b & 0xff);
                    if (temp.length() == 1) {
                        temp = "0" + temp;
                    }
                    result += temp;
                }
                return result;
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
            return "";
        }

    }

}
