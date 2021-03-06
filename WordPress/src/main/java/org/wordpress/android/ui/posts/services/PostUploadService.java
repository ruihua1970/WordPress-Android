package org.wordpress.android.ui.posts.services;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.support.v4.app.NotificationCompat.Builder;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.android.volley.VolleyError;
import com.wordpress.rest.RestRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.wordpress.android.Constants;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.analytics.AnalyticsTracker;
import org.wordpress.android.models.Blog;
import org.wordpress.android.models.FeatureSet;
import org.wordpress.android.models.Post;
import org.wordpress.android.models.PostLocation;
import org.wordpress.android.models.PostStatus;
import org.wordpress.android.ui.posts.PostsListActivity;
import org.wordpress.android.ui.posts.services.PostEvents.PostUploadEnded;
import org.wordpress.android.ui.posts.services.PostEvents.PostUploadStarted;
import org.wordpress.android.util.AnalyticsUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.CrashlyticsUtils;
import org.wordpress.android.util.DisplayUtils;
import org.wordpress.android.util.ImageUtils;
import org.wordpress.android.util.JSONUtils;
import org.wordpress.android.util.MediaUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.SystemServiceFactory;
import org.wordpress.android.util.WPMeShortlinks;
import org.wordpress.android.util.helpers.MediaFile;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlrpc.android.ApiHelper;
import org.xmlrpc.android.XMLRPCClient;
import org.xmlrpc.android.XMLRPCClientInterface;
import org.xmlrpc.android.XMLRPCException;
import org.xmlrpc.android.XMLRPCFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.greenrobot.event.EventBus;

public class PostUploadService extends Service {
    private static Context mContext;
    private static final ArrayList<Post> mPostsList = new ArrayList<Post>();
    private static Post mCurrentUploadingPost = null;
    private UploadPostTask mCurrentTask = null;
    private FeatureSet mFeatureSet;

    private Post mPost;
    private Blog mBlog;
    private PostUploadNotifier mPostUploadNotifier;

    private String mErrorMessage = "";
    private boolean mIsMediaError = false;
    private boolean mErrorUnavailableVideoPress = false;
    private int featuredImageID = -1;

    private JSONObject jsonResult;

    public static void addPostToUpload(Post currentPost) {
        synchronized (mPostsList) {
            mPostsList.add(currentPost);
        }
    }

    /*
     * returns true if the passed post is either uploading or waiting to be uploaded
     */
    public static boolean isPostUploading(long localPostId) {
        // first check the currently uploading post
        if (mCurrentUploadingPost != null && mCurrentUploadingPost.getLocalTablePostId() == localPostId) {
            return true;
        }
        // then check the list of posts waiting to be uploaded
        if (mPostsList.size() > 0) {
            synchronized (mPostsList) {
                for (Post post : mPostsList) {
                    if (post.getLocalTablePostId() == localPostId) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mContext = this.getApplicationContext();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Cancel current task, it will reset post from "uploading" to "local draft"
        if (mCurrentTask != null) {
            AppLog.d(T.POSTS, "cancelling current upload task");
            mCurrentTask.cancel(true);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (mPostsList) {
            if (mPostsList.size() == 0 || mContext == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        uploadNextPost();
        // We want this service to continue running until it is explicitly stopped, so return sticky.
        return START_STICKY;
    }

    private FeatureSet synchronousGetFeatureSet() {
        if (WordPress.getCurrentBlog() == null || !WordPress.getCurrentBlog().isDotcomFlag()) {
            return null;
        }
        ApiHelper.GetFeatures task = new ApiHelper.GetFeatures();
        List<Object> apiArgs = new ArrayList<Object>();
        apiArgs.add(WordPress.getCurrentBlog());
        mFeatureSet = task.doSynchronously(apiArgs);

        return mFeatureSet;
    }

    private void uploadNextPost() {
        synchronized (mPostsList) {
            if (mCurrentTask == null) { //make sure nothing is running
                mCurrentUploadingPost = null;
                if (mPostsList.size() > 0) {
                    mCurrentUploadingPost = mPostsList.remove(0);
                    //mCurrentTask = new UploadPostTask();
                    //mCurrentTask.execute(mCurrentUploadingPost);
                    uploadPost(mCurrentUploadingPost);
                } else {
                    stopSelf();
                }
            }
        }
    }

    private void postUploaded() {
        synchronized (mPostsList) {
            mCurrentTask = null;
            mCurrentUploadingPost = null;
        }
        uploadNextPost();
    }

    /***
     * update the tags the user is followed - also handles recommended (popular) tags since
     * they're included in the response
     */
    private void uploadPost(final Post mCurrentUploadingPost) {

        mErrorUnavailableVideoPress = false;
        mPost = mCurrentUploadingPost;

        mPostUploadNotifier = new PostUploadNotifier(mPost);
        String postTitle = TextUtils.isEmpty(mPost.getTitle()) ? getString(R.string.untitled) : mPost.getTitle();
        String uploadingPostTitle = String.format(getString(R.string.posting_post), postTitle);
        String uploadingPostMessage = String.format(
                getString(R.string.sending_content),
                mPost.isPage() ? getString(R.string.page).toLowerCase() : getString(R.string.post).toLowerCase()
        );
        mPostUploadNotifier.updateNotificationMessage(uploadingPostTitle, uploadingPostMessage);

        mBlog = WordPress.wpDB.instantiateBlogByLocalId(mPost.getLocalTableBlogId());
        if (mBlog == null) {
            mErrorMessage = mContext.getString(R.string.blog_not_found);
            //return false;
        }

        String descriptionContent = processPostMedia(mPost.getDescription());



        Map<String, String> contentStruct = new HashMap<>();
        contentStruct.put("title", mPost.getTitle());
        long pubDate = mPost.getDate_created_gmt();
        if (pubDate != 0) {
            Date date_created_gmt = new Date(pubDate);
                /*contentStruct.put("date_created_gmt", date_created_gmt);
                Date dateCreated = new Date(pubDate + (date_created_gmt.getTimezoneOffset() * 60000));
                contentStruct.put("dateCreated", dateCreated);*/
        }
        // gets rid of the weird character android inserts after images
        descriptionContent = descriptionContent.replaceAll("\uFFFC", "");

        contentStruct.put("description", descriptionContent);


        String path = "posts";

        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                handleUploadPostsResponse(jsonObject,mPost);
            }
        };

        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(AppLog.T.READER, volleyError);
                //taskCompleted(UpdateTask.TAGS);
            }
        };
        AppLog.d(T.POSTS, "post service > uploading posts");
        //WordPress.getRestClientUtilsV1_2().get("read/menu", null, null, listener, errorListener);
        EventBus.getDefault().post(new PostUploadStarted(mPost.getLocalTableBlogId()));
        WordPress.getRestClientUtilsV1_1().post(path, contentStruct, null, listener, errorListener);
    }

    private void handleUploadPostsResponse(final JSONObject jsonObject,final Post mPost) {
        new Thread() {
            @Override
            public void run() {

            }
        }.start();
    }

    /**
     * Finds media in post content, uploads them, and returns the HTML to insert in the post
     */
    private String processPostMedia(String postContent) {
        String imageTagsPattern = "<img[^>]+android-uri\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>";
        Pattern pattern = Pattern.compile(imageTagsPattern);
        Matcher matcher = pattern.matcher(postContent);

        int totalMediaItems = 0;
        List<String> imageTags = new ArrayList<String>();
        while (matcher.find()) {
            imageTags.add(matcher.group());
            totalMediaItems++;
        }

        mPostUploadNotifier.setTotalMediaItems(totalMediaItems);

        int mediaItemCount = 0;
        for (String tag : imageTags) {
            Pattern p = Pattern.compile("android-uri=\"([^\"]+)\"");
            Matcher m = p.matcher(tag);
            if (m.find()) {
                String imageUri = m.group(1);
                if (!imageUri.equals("")) {
                    Log.d("xxx","bbb");
                    MediaFile mediaFile = WordPress.wpDB.getMediaFile(imageUri, mPost);
                    if (mediaFile != null) {
                        // Get image thumbnail for notification icon
                        Log.d("ppp","ooo");
                        Bitmap imageIcon = ImageUtils.getWPImageSpanThumbnailFromFilePath(
                                mContext,
                                imageUri,
                                DisplayUtils.dpToPx(mContext, 128)
                        );

                        // Crop the thumbnail to be squared in the center
                        if (imageIcon != null) {
                            int squaredSize = DisplayUtils.dpToPx(mContext, 64);
                            imageIcon = ThumbnailUtils.extractThumbnail(imageIcon, squaredSize, squaredSize);
                            //mLatestIcon = imageIcon;//comment by eiri
                        }

                        mediaItemCount++;
                        mPostUploadNotifier.setCurrentMediaItem(mediaItemCount);
                        mPostUploadNotifier.updateNotificationIcon(imageIcon);

                        String mediaUploadOutput;
                        if (mediaFile.isVideo()) {
                            //mHasVideo = true;//comment by eiri
                            mediaUploadOutput = uploadVideo(mediaFile);
                        } else {
                            //mHasImage = true;//comment by eiri
                            mediaUploadOutput = uploadImage(mediaFile);
                        }

                        if (mediaUploadOutput != null) {
                            postContent = postContent.replace(tag, mediaUploadOutput);
                        } else {
                            postContent = postContent.replace(tag, "");
                            mIsMediaError = true;
                        }
                    }
                }
            }
        }

        return postContent;
    }

    private String uploadImage(MediaFile mediaFile) {
        AppLog.d(T.POSTS, "uploadImage: " + mediaFile.getFilePath());

        if (mediaFile.getFilePath() == null) {
            return null;
        }

        Uri imageUri = Uri.parse(mediaFile.getFilePath());
        File imageFile = null;
        String mimeType = "", path = "";

        if (imageUri.toString().contains("content:")) {
            String[] projection = new String[]{Images.Media._ID, Images.Media.DATA, Images.Media.MIME_TYPE};

            Cursor cur = mContext.getContentResolver().query(imageUri, projection, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int dataColumn = cur.getColumnIndex(Images.Media.DATA);
                int mimeTypeColumn = cur.getColumnIndex(Images.Media.MIME_TYPE);

                String thumbData = cur.getString(dataColumn);
                mimeType = cur.getString(mimeTypeColumn);
                imageFile = new File(thumbData);
                path = thumbData;
                mediaFile.setFilePath(imageFile.getPath());
            }
        } else { // file is not in media library
            path = imageUri.toString().replace("file://", "");
            imageFile = new File(path);
            mediaFile.setFilePath(path);
        }

        // check if the file exists
        if (imageFile == null) {
            mErrorMessage = mContext.getString(R.string.file_not_found);
            return null;
        }

        if (TextUtils.isEmpty(mimeType)) {
            mimeType = MediaUtils.getMediaFileMimeType(imageFile);
        }
        String fileName = MediaUtils.getMediaFileName(imageFile, mimeType);
        String fileExtension = MimeTypeMap.getFileExtensionFromUrl(fileName).toLowerCase();

        int orientation = ImageUtils.getImageOrientation(mContext, path);

        String resizedPictureURL = null;

        // We need to upload a resized version of the picture when the blog settings != original size, or when
        // the user has selected a smaller size for the current picture in the picture settings screen
        // We won't resize gif images to keep them awesome.
        boolean shouldUploadResizedVersion = false;
        // If it's not a gif and blog don't keep original size, there is a chance we need to resize
        if (!mimeType.equals("image/gif") && !mBlog.getMaxImageWidth().equals("Original Size")) {
            //check the picture settings
            int pictureSettingWidth = mediaFile.getWidth();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            int imageHeight = options.outHeight;
            int imageWidth = options.outWidth;
            int[] dimensions = {imageWidth, imageHeight};
            if (dimensions[0] != 0 && dimensions[0] != pictureSettingWidth) {
                shouldUploadResizedVersion = true;
            }
        }

        boolean shouldAddImageWidthCSS = false;

        if (shouldUploadResizedVersion) {
            MediaFile resizedMediaFile = new MediaFile(mediaFile);
            // Create resized image
            byte[] bytes = ImageUtils.createThumbnailFromUri(mContext, imageUri, resizedMediaFile.getWidth(),
                    fileExtension, orientation);

            if (bytes == null) {
                // We weren't able to resize the image, so we will upload the full size image with css to resize it
                shouldUploadResizedVersion = false;
                shouldAddImageWidthCSS = true;
            } else {
                // Save temp image
                String tempFilePath;
                File resizedImageFile;
                try {
                    resizedImageFile = File.createTempFile("wp-image-", fileExtension);
                    FileOutputStream out = new FileOutputStream(resizedImageFile);

                    out.write(bytes);
                    out.close();
                    tempFilePath = resizedImageFile.getPath();
                } catch (IOException e) {
                    AppLog.w(T.POSTS, "failed to create image temp file");
                    mErrorMessage = mContext.getString(R.string.error_media_upload);
                    return null;
                }

                // upload resized picture
                if (!TextUtils.isEmpty(tempFilePath)) {
                    resizedMediaFile.setFilePath(tempFilePath);
                    Map<String, Object> parameters = new HashMap<String, Object>();
                    AppLog.w(T.POSTS, "ccccc");
                    parameters.put("name", fileName);
                    parameters.put("type", mimeType);
                    //parameters.put("bits",resizedImageFile.getPath().getBytes());
                    parameters.put("bits",resizedImageFile);
                    parameters.put("overwrite", true);

                    resizedPictureURL = uploadImageFile(parameters, resizedMediaFile, mBlog);
                    if (resizedPictureURL == null) {
                        AppLog.w(T.POSTS, "failed to upload resized picture");
                        return null;
                    } else if (resizedImageFile.exists()) {
                        resizedImageFile.delete();
                    }
                } else {
                    AppLog.w(T.POSTS, "failed to create resized picture");
                    mErrorMessage = mContext.getString(R.string.out_of_memory);
                    return null;
                }
            }
        }

        String fullSizeUrl = null;

        // Upload the full size picture if "Original Size" is selected in settings,
        // or if 'link to full size' is checked.
        if (!shouldUploadResizedVersion || mBlog.isFullSizeImage()) {
            //AppLog.w(T.POSTS, "ccccc" + Base64.encodeToString(mediaFile.getFilePath().getBytes(),Base64.DEFAULT));

          /*  String base64String =null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                ObjectOutputStream os = new ObjectOutputStream(baos);
                os.writeObject(mediaFile);
                // 之后进行base64编码
                base64String = Base64.encodeToString(baos.toByteArray(),
                        Base64.DEFAULT);
                AppLog.w(T.POSTS, "ccccc22" +base64String);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                AppLog.w(T.POSTS, "ccccc22" + e);
            }*/



            Map<String, Object> parameters = new HashMap<String, Object>();
            parameters.put("name", fileName);
            parameters.put("type", mimeType);
            //parameters.put("bits", Base64.encodeToString(mediaFile));
            //parameters.put("bits",Base64.encodeToString(mediaFile.getFilePath().getBytes(),Base64.DEFAULT));
            parameters.put("overwrite", true);

            fullSizeUrl = uploadImageFile(parameters, mediaFile, mBlog);
            if (fullSizeUrl == null) {
                mErrorMessage = mContext.getString(R.string.error_media_upload);
                return null;
            }
        }

        return mediaFile.getImageHtmlForUrls(fullSizeUrl, resizedPictureURL, shouldAddImageWidthCSS);
    }

    private String uploadVideo(MediaFile mediaFile) {
        // create temp file for media upload
        String tempFileName = "wp-" + System.currentTimeMillis();
        try {
            mContext.openFileOutput(tempFileName, Context.MODE_PRIVATE);
        } catch (FileNotFoundException e) {
            mErrorMessage = getResources().getString(R.string.file_error_create);
            return null;
        }

        if (mediaFile.getFilePath() == null) {
            mErrorMessage = mContext.getString(R.string.error_media_upload);
            return null;
        }

        Uri videoUri = Uri.parse(mediaFile.getFilePath());
        File videoFile = null;
        String mimeType = "", xRes = "", yRes = "";

        if (videoUri.toString().contains("content:")) {
            String[] projection = new String[]{Video.Media._ID, Video.Media.DATA, Video.Media.MIME_TYPE,
                    Video.Media.RESOLUTION};
            Cursor cur = mContext.getContentResolver().query(videoUri, projection, null, null, null);

            if (cur != null && cur.moveToFirst()) {
                int dataColumn = cur.getColumnIndex(Video.Media.DATA);
                int mimeTypeColumn = cur.getColumnIndex(Video.Media.MIME_TYPE);
                int resolutionColumn = cur.getColumnIndex(Video.Media.RESOLUTION);

                mediaFile = new MediaFile();

                String thumbData = cur.getString(dataColumn);
                mimeType = cur.getString(mimeTypeColumn);

                videoFile = new File(thumbData);
                mediaFile.setFilePath(videoFile.getPath());
                String resolution = cur.getString(resolutionColumn);
                if (resolution != null) {
                    String[] resolutions = resolution.split("x");
                    if (resolutions.length >= 2) {
                        xRes = resolutions[0];
                        yRes = resolutions[1];
                    }
                } else {
                    // set the width of the video to the thumbnail width, else 640x480
                    if (!mBlog.getMaxImageWidth().equals("Original Size")) {
                        xRes = mBlog.getMaxImageWidth();
                        yRes = String.valueOf(Math.round(Integer.valueOf(mBlog.getMaxImageWidth()) * 0.75));
                    } else {
                        xRes = "640";
                        yRes = "480";
                    }
                }
            }
        } else { // file is not in media library
            String filePath = videoUri.toString().replace("file://", "");
            mediaFile.setFilePath(filePath);
            videoFile = new File(filePath);
        }

        if (videoFile == null) {
            mErrorMessage = mContext.getResources().getString(R.string.error_media_upload);
            return null;
        }

        if (TextUtils.isEmpty(mimeType)) {
            mimeType = MediaUtils.getMediaFileMimeType(videoFile);
        }
        String videoName = MediaUtils.getMediaFileName(videoFile, mimeType);

        // try to upload the video
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("name", videoName);
        m.put("type", mimeType);
        m.put("bits", mediaFile);
        m.put("overwrite", true);

        Object[] params = {1, mBlog.getUsername(), mBlog.getPassword(), m};

        FeatureSet featureSet = synchronousGetFeatureSet();
        boolean selfHosted = WordPress.currentBlog != null && !WordPress.currentBlog.isDotcomFlag();
        boolean isVideoEnabled = selfHosted || (featureSet != null && mFeatureSet.isVideopressEnabled());
        if (isVideoEnabled) {
            File tempFile;
            try {
                String fileExtension = MimeTypeMap.getFileExtensionFromUrl(videoName);
                tempFile = createTempUploadFile(fileExtension);
            } catch (IOException e) {
                mErrorMessage = getResources().getString(R.string.file_error_create);
                return null;
            }

            Object result = uploadFileHelper(m, tempFile);
            Map<?, ?> resultMap = (HashMap<?, ?>) result;
            if (resultMap != null && resultMap.containsKey("url")) {
                String resultURL = resultMap.get("url").toString();
                if (resultMap.containsKey(MediaFile.VIDEOPRESS_SHORTCODE_ID)) {
                    resultURL = resultMap.get(MediaFile.VIDEOPRESS_SHORTCODE_ID).toString() + "\n";
                } else {
                    resultURL = String.format(
                            "<video width=\"%s\" height=\"%s\" controls=\"controls\"><source src=\"%s\" type=\"%s\" /><a href=\"%s\">Click to view video</a>.</video>",
                            xRes, yRes, resultURL, mimeType, resultURL);
                }

                return resultURL;
            } else {
                mErrorMessage = mContext.getResources().getString(R.string.error_media_upload);
                return null;
            }
        } else {
            mErrorMessage = getString(R.string.media_no_video_message);
            mErrorUnavailableVideoPress = true;
            return null;
        }
    }


    private void setUploadPostErrorMessage(Exception e) {
        mErrorMessage = String.format(mContext.getResources().getText(R.string.error_upload).toString(),
                mPost.isPage() ? mContext.getResources().getText(R.string.page).toString() :
                        mContext.getResources().getText(R.string.post).toString()) + " " + e.getMessage();
        mIsMediaError = false;
        AppLog.e(T.EDITOR, mErrorMessage, e);
    }

    private String uploadImageFile(Map<String, Object> pictureParams, MediaFile mf, Blog blog) {
        // create temporary upload file
        File tempFile;
        try {
            String fileExtension = MimeTypeMap.getFileExtensionFromUrl(mf.getFileName());
            tempFile = createTempUploadFile(fileExtension);
        } catch (IOException e) {
            mIsMediaError = true;
            mErrorMessage = mContext.getString(R.string.file_not_found);
            return null;
        }

        //Object[] params = {1, blog.getUsername(), blog.getPassword(), pictureParams};
        //Object result = uploadFileHelper(pictureParams, tempFile);
        JSONObject result = uploadFileHelper(pictureParams, tempFile);
        if (result == null) {
            mIsMediaError = true;
            return null;
        }

        //Map<?, ?> contentHash = (HashMap<?, ?>) result;
        //JSONUtils.getString(result, "content");
        String pictureURL = JSONUtils.getString(result, "url");
        //String pictureURL = contentHash.get("url").toString();

       /* if (mf.isFeatured()) {
            try {
                if (contentHash.get("id") != null) {
                    featuredImageID = Integer.parseInt(contentHash.get("id").toString());
                    if (!mf.isFeaturedInPost())
                        return "";
                }
            } catch (NumberFormatException e) {
                AppLog.e(T.POSTS, e);
            }
        }*/

        return pictureURL;
    }

    private JSONObject uploadFileHelper(Map<String, Object> params, final File tempFile) {

        //String base64String =null;
        /* ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                ObjectOutputStream os = new ObjectOutputStream(baos);
                os.writeObject(tempFile);
                // 之后进行base64编码
                base64String = Base64.encodeToString(baos.toByteArray(),
                        Base64.DEFAULT);
                AppLog.w(T.POSTS, "ccccc22" +base64String);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                AppLog.w(T.POSTS, "ccccc22" + e);
            }*/

        //byte[] bytes=null;
        //try {
            //resizedImageFile = File.createTempFile("wp-image-", fileExtension);
           /* Bitmap myImg = BitmapFactory.decodeFile(tempFile.getPath());
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            myImg.compress(Bitmap.CompressFormat.JPEG, 50, stream);
            byte[] byte_arr = stream.toByteArray();*/
            // Encode Image to String

            //encodedString = Base64.encodeToString(loadFile_byte(tempFile), 0);
            //tempFilePath = resizedImageFile.getPath();


        //base64String = Base64.encodeToString(bytes, 0);
        params.put("bits",loadFile_base64String(tempFile));
      //JSONObject result;
       JSONObject jsonData =  new JSONObject(params);

        //JSONObject jobj = JSONUtil.parseJavaMap(data);

        String path = "media";

        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {

                jsonResult = jsonObject;
                // remove the temporary upload file now that we're done with it
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
            }
        };

        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(AppLog.T.READER, volleyError);
                //mCallback.onFailure(mErrorType, mErrorMessage, mThrowable);
                //taskCompleted(UpdateTask.TAGS);
            }
        };
        AppLog.d(AppLog.T.READER, "post service > uploading posts");
        //WordPress.getRestClientUtilsV1_2().get("read/menu", null, null, listener, errorListener);
        WordPress.getRestClientUtilsV1_1().post(path, jsonData, null, listener, errorListener);
        //getTempFile(mContext);
        return jsonResult;
    }


    /**
     * 加载本地文件,并转换为byte数组
     * @return
     */
    public static String loadFile_base64String(final File file) {
        //File file = new File("d:/11.jpg");

        String base64String =null;
         ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                ObjectOutputStream os = new ObjectOutputStream(baos);
                os.writeObject(file);
                // 之后进行base64编码
                base64String = Base64.encodeToString(baos.toByteArray(),
                        Base64.DEFAULT);
                AppLog.w(T.POSTS, "ccccc22" +base64String);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                AppLog.w(T.POSTS, "ccccc22" + e);
            }

        return base64String ;
    }

    private class UploadPostTask extends AsyncTask<Post, Boolean, Boolean> {
        private Post mPost;
        private Blog mBlog;
        private PostUploadNotifier mPostUploadNotifier;

        private String mErrorMessage = "";
        private boolean mIsMediaError = false;
        private boolean mErrorUnavailableVideoPress = false;
        private int featuredImageID = -1;
        private XMLRPCClientInterface mClient;

        // Used when the upload succeed
        private Bitmap mLatestIcon;

        // Used for analytics
        private boolean mHasImage, mHasVideo, mHasCategory;

        @Override
        protected void onPostExecute(Boolean postUploadedSuccessfully) {
            if (postUploadedSuccessfully) {
                WordPress.wpDB.deleteMediaFilesForPost(mPost);
                mPostUploadNotifier.cancelNotification();
                mPostUploadNotifier.updateNotificationSuccess(mPost, mLatestIcon);
            } else {
                mPostUploadNotifier.updateNotificationError(mErrorMessage, mIsMediaError, mPost.isPage(),
                        mErrorUnavailableVideoPress);
            }

            postUploaded();
            EventBus.getDefault().post(new PostUploadEnded(postUploadedSuccessfully, mPost.getLocalTableBlogId()));
        }

        @Override
        protected void onCancelled(Boolean aBoolean) {
            super.onCancelled(aBoolean);
            // mPostUploadNotifier and mPost can be null if onCancelled is called before doInBackground
            if (mPostUploadNotifier != null && mPost != null) {
                mPostUploadNotifier.updateNotificationError(mErrorMessage, mIsMediaError, mPost.isPage(),
                        mErrorUnavailableVideoPress);
            }
        }

        @Override
        protected Boolean doInBackground(Post... posts) {
            mErrorUnavailableVideoPress = false;
            mPost = posts[0];

            mPostUploadNotifier = new PostUploadNotifier(mPost);
            String postTitle = TextUtils.isEmpty(mPost.getTitle()) ? getString(R.string.untitled) : mPost.getTitle();
            String uploadingPostTitle = String.format(getString(R.string.posting_post), postTitle);
            String uploadingPostMessage = String.format(
                    getString(R.string.sending_content),
                    mPost.isPage() ? getString(R.string.page).toLowerCase() : getString(R.string.post).toLowerCase()
            );
            mPostUploadNotifier.updateNotificationMessage(uploadingPostTitle, uploadingPostMessage);

            mBlog = WordPress.wpDB.instantiateBlogByLocalId(mPost.getLocalTableBlogId());
            if (mBlog == null) {
                mErrorMessage = mContext.getString(R.string.blog_not_found);
                return false;
            }

            // Create the XML-RPC client
            mClient = XMLRPCFactory.instantiate(mBlog.getUri(), mBlog.getHttpuser(),
                    mBlog.getHttppassword());

            if (TextUtils.isEmpty(mPost.getPostStatus())) {
                mPost.setPostStatus(PostStatus.toString(PostStatus.PUBLISHED));
            }

            String descriptionContent = processPostMedia(mPost.getDescription());

            String moreContent = "";
            if (!TextUtils.isEmpty(mPost.getMoreText())) {
                moreContent = processPostMedia(mPost.getMoreText());
            }

            mPostUploadNotifier.updateNotificationMessage(uploadingPostTitle, uploadingPostMessage);

            // If media file upload failed, let's stop here and prompt the user
            if (mIsMediaError) {
                return false;
            }

            JSONArray categoriesJsonArray = mPost.getJSONCategories();
            String[] postCategories = null;
            if (categoriesJsonArray != null) {
                if (categoriesJsonArray.length() > 0) {
                    mHasCategory = true;
                }

                postCategories = new String[categoriesJsonArray.length()];
                for (int i = 0; i < categoriesJsonArray.length(); i++) {
                    try {
                        postCategories[i] = TextUtils.htmlEncode(categoriesJsonArray.getString(i));
                    } catch (JSONException e) {
                        AppLog.e(T.POSTS, e);
                    }
                }
            }

            Map<String, Object> contentStruct = new HashMap<String, Object>();

            if (!mPost.isPage() && mPost.isLocalDraft()) {
                // add the tagline
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);

                if (prefs.getBoolean(getString(R.string.pref_key_post_sig_enabled), false)) {
                    String tagline = prefs.getString(getString(R.string.pref_key_post_sig), "");
                    if (!TextUtils.isEmpty(tagline)) {
                        String tag = "\n\n<span class=\"post_sig\">" + tagline + "</span>\n\n";
                        if (TextUtils.isEmpty(moreContent))
                            descriptionContent += tag;
                        else
                            moreContent += tag;
                    }
                }
            }

            // Post format
            if (!mPost.isPage()) {
                if (!TextUtils.isEmpty(mPost.getPostFormat())) {
                    contentStruct.put("wp_post_format", mPost.getPostFormat());
                }
            }

            contentStruct.put("post_type", (mPost.isPage()) ? "page" : "post");
            contentStruct.put("title", mPost.getTitle());
            long pubDate = mPost.getDate_created_gmt();
            if (pubDate != 0) {
                Date date_created_gmt = new Date(pubDate);
                contentStruct.put("date_created_gmt", date_created_gmt);
                Date dateCreated = new Date(pubDate + (date_created_gmt.getTimezoneOffset() * 60000));
                contentStruct.put("dateCreated", dateCreated);
            }

            if (!TextUtils.isEmpty(moreContent)) {
                descriptionContent = descriptionContent.trim() + "<!--more-->" + moreContent;
                mPost.setMoreText("");
            }

            // get rid of the p and br tags that the editor adds.
            if (mPost.isLocalDraft()) {
                descriptionContent = descriptionContent.replace("<p>", "").replace("</p>", "\n").replace("<br>", "");
            }

            // gets rid of the weird character android inserts after images
            descriptionContent = descriptionContent.replaceAll("\uFFFC", "");

            contentStruct.put("description", descriptionContent);
            if (!mPost.isPage()) {
                contentStruct.put("mt_keywords", mPost.getKeywords());

                if (postCategories != null && postCategories.length > 0) {
                    contentStruct.put("categories", postCategories);
                }
            }

            contentStruct.put("mt_excerpt", mPost.getPostExcerpt());
            contentStruct.put((mPost.isPage()) ? "page_status" : "post_status", mPost.getPostStatus());

            // Geolocation
            if (mPost.supportsLocation()) {
                JSONObject remoteGeoLatitude = mPost.getCustomField("geo_latitude");
                JSONObject remoteGeoLongitude = mPost.getCustomField("geo_longitude");
                JSONObject remoteGeoPublic = mPost.getCustomField("geo_public");

                Map<Object, Object> hLatitude = new HashMap<Object, Object>();
                Map<Object, Object> hLongitude = new HashMap<Object, Object>();
                Map<Object, Object> hPublic = new HashMap<Object, Object>();

                try {
                    if (remoteGeoLatitude != null) {
                        hLatitude.put("id", remoteGeoLatitude.getInt("id"));
                    }

                    if (remoteGeoLongitude != null) {
                        hLongitude.put("id", remoteGeoLongitude.getInt("id"));
                    }

                    if (remoteGeoPublic != null) {
                        hPublic.put("id", remoteGeoPublic.getInt("id"));
                    }

                    if (mPost.hasLocation()) {
                        PostLocation location = mPost.getLocation();
                        if (!hLatitude.containsKey("id")) {
                            hLatitude.put("key", "geo_latitude");
                        }

                        if (!hLongitude.containsKey("id")) {
                            hLongitude.put("key", "geo_longitude");
                        }

                        if (!hPublic.containsKey("id")) {
                            hPublic.put("key", "geo_public");
                        }

                        hLatitude.put("value", location.getLatitude());
                        hLongitude.put("value", location.getLongitude());
                        hPublic.put("value", 1);
                    }
                } catch (JSONException e) {
                    AppLog.e(T.EDITOR, e);
                }

                if (!hLatitude.isEmpty() && !hLongitude.isEmpty() && !hPublic.isEmpty()) {
                    Object[] geo = {hLatitude, hLongitude, hPublic};
                    contentStruct.put("custom_fields", geo);
                }
            }

            // featured image
            if (featuredImageID != -1) {
                contentStruct.put("wp_post_thumbnail", featuredImageID);
            }

            if (!TextUtils.isEmpty(mPost.getQuickPostType())) {
                mClient.addQuickPostHeader(mPost.getQuickPostType());
            }

            contentStruct.put("wp_password", mPost.getPassword());

            Object[] params;
            if (mPost.isLocalDraft())
                params = new Object[]{mBlog.getRemoteBlogId(), mBlog.getUsername(), mBlog.getPassword(),
                        contentStruct, false};
            else
                params = new Object[]{mPost.getRemotePostId(), mBlog.getUsername(), mBlog.getPassword(), contentStruct,
                        false};

            try {
                EventBus.getDefault().post(new PostUploadStarted(mPost.getLocalTableBlogId()));

                if (mPost.isLocalDraft()) {
                    Object object = mClient.call("metaWeblog.newPost", params);
                    if (object instanceof String) {
                        mPost.setRemotePostId((String) object);
                    }
                } else {
                    mClient.call("metaWeblog.editPost", params);
                }

                // Track any Analytics before modifying the post
                trackUploadAnalytics();

                mPost.setLocalDraft(false);
                mPost.setLocalChange(false);
                WordPress.wpDB.updatePost(mPost);

                // request the new/updated post from the server to ensure local copy matches server
                ApiHelper.updateSinglePost(mBlog.getLocalTableBlogId(), mPost.getRemotePostId(), mPost.isPage());

                return true;
            } catch (final XMLRPCException e) {
                setUploadPostErrorMessage(e);
            } catch (IOException e) {
                setUploadPostErrorMessage(e);
            } catch (XmlPullParserException e) {
                setUploadPostErrorMessage(e);
            }

            return false;
        }

        private void trackUploadAnalytics() {
            mPost.getStatusEnum();

            boolean isFirstTimePublishing = false;
            if (mPost.hasChangedFromDraftToPublished() ||
                    (mPost.isLocalDraft() && mPost.getStatusEnum() == PostStatus.PUBLISHED)) {
                isFirstTimePublishing = true;
            }

            if (isFirstTimePublishing) {
                // Calculate the words count
                Map<String, Object> properties = new HashMap<String, Object>();
                properties.put("word_count", AnalyticsUtils.getWordCount(mPost.getContent()));

                if (mHasImage) {
                    properties.put("with_photos", true);
                }
                if (mHasVideo) {
                    properties.put("with_videos", true);
                }
                if (mHasCategory) {
                    properties.put("with_categories", true);
                }
                if (!TextUtils.isEmpty(mPost.getKeywords())) {
                    properties.put("with_tags", true);
                }

                AnalyticsTracker.track(AnalyticsTracker.Stat.EDITOR_PUBLISHED_POST, properties);
            }
        }

        /**
         * Finds media in post content, uploads them, and returns the HTML to insert in the post
         */
        private String processPostMedia(String postContent) {
            String imageTagsPattern = "<img[^>]+android-uri\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>";
            Pattern pattern = Pattern.compile(imageTagsPattern);
            Matcher matcher = pattern.matcher(postContent);

            int totalMediaItems = 0;
            List<String> imageTags = new ArrayList<String>();
            while (matcher.find()) {
                imageTags.add(matcher.group());
                totalMediaItems++;
            }

            mPostUploadNotifier.setTotalMediaItems(totalMediaItems);

            int mediaItemCount = 0;
            for (String tag : imageTags) {
                Pattern p = Pattern.compile("android-uri=\"([^\"]+)\"");
                Matcher m = p.matcher(tag);
                if (m.find()) {
                    String imageUri = m.group(1);
                    if (!imageUri.equals("")) {
                        MediaFile mediaFile = WordPress.wpDB.getMediaFile(imageUri, mPost);
                        if (mediaFile != null) {
                            // Get image thumbnail for notification icon
                            Bitmap imageIcon = ImageUtils.getWPImageSpanThumbnailFromFilePath(
                                    mContext,
                                    imageUri,
                                    DisplayUtils.dpToPx(mContext, 128)
                            );

                            // Crop the thumbnail to be squared in the center
                            if (imageIcon != null) {
                                int squaredSize = DisplayUtils.dpToPx(mContext, 64);
                                imageIcon = ThumbnailUtils.extractThumbnail(imageIcon, squaredSize, squaredSize);
                                mLatestIcon = imageIcon;
                            }

                            mediaItemCount++;
                            mPostUploadNotifier.setCurrentMediaItem(mediaItemCount);
                            mPostUploadNotifier.updateNotificationIcon(imageIcon);

                            String mediaUploadOutput;
                            if (mediaFile.isVideo()) {
                                mHasVideo = true;
                                mediaUploadOutput = uploadVideo(mediaFile);
                            } else {
                                mHasImage = true;
                                mediaUploadOutput = uploadImage(mediaFile);
                            }

                            if (mediaUploadOutput != null) {
                                postContent = postContent.replace(tag, mediaUploadOutput);
                            } else {
                                postContent = postContent.replace(tag, "");
                                mIsMediaError = true;
                            }
                        }
                    }
                }
            }

            return postContent;
        }

        private String uploadImage(MediaFile mediaFile) {
            AppLog.d(T.POSTS, "uploadImage: " + mediaFile.getFilePath());

            if (mediaFile.getFilePath() == null) {
                return null;
            }

            Uri imageUri = Uri.parse(mediaFile.getFilePath());
            File imageFile = null;
            String mimeType = "", path = "";

            if (imageUri.toString().contains("content:")) {
                String[] projection = new String[]{Images.Media._ID, Images.Media.DATA, Images.Media.MIME_TYPE};

                Cursor cur = mContext.getContentResolver().query(imageUri, projection, null, null, null);
                if (cur != null && cur.moveToFirst()) {
                    int dataColumn = cur.getColumnIndex(Images.Media.DATA);
                    int mimeTypeColumn = cur.getColumnIndex(Images.Media.MIME_TYPE);

                    String thumbData = cur.getString(dataColumn);
                    mimeType = cur.getString(mimeTypeColumn);
                    imageFile = new File(thumbData);
                    path = thumbData;
                    mediaFile.setFilePath(imageFile.getPath());
                }
            } else { // file is not in media library
                path = imageUri.toString().replace("file://", "");
                imageFile = new File(path);
                mediaFile.setFilePath(path);
            }

            // check if the file exists
            if (imageFile == null) {
                mErrorMessage = mContext.getString(R.string.file_not_found);
                return null;
            }

            if (TextUtils.isEmpty(mimeType)) {
                mimeType = MediaUtils.getMediaFileMimeType(imageFile);
            }
            String fileName = MediaUtils.getMediaFileName(imageFile, mimeType);
            String fileExtension = MimeTypeMap.getFileExtensionFromUrl(fileName).toLowerCase();

            int orientation = ImageUtils.getImageOrientation(mContext, path);

            String resizedPictureURL = null;

            // We need to upload a resized version of the picture when the blog settings != original size, or when
            // the user has selected a smaller size for the current picture in the picture settings screen
            // We won't resize gif images to keep them awesome.
            boolean shouldUploadResizedVersion = false;
            // If it's not a gif and blog don't keep original size, there is a chance we need to resize
            if (!mimeType.equals("image/gif") && !mBlog.getMaxImageWidth().equals("Original Size")) {
                //check the picture settings
                int pictureSettingWidth = mediaFile.getWidth();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, options);
                int imageHeight = options.outHeight;
                int imageWidth = options.outWidth;
                int[] dimensions = {imageWidth, imageHeight};
                if (dimensions[0] != 0 && dimensions[0] != pictureSettingWidth) {
                    shouldUploadResizedVersion = true;
                }
            }

            boolean shouldAddImageWidthCSS = false;

            if (shouldUploadResizedVersion) {
                MediaFile resizedMediaFile = new MediaFile(mediaFile);
                // Create resized image
                byte[] bytes = ImageUtils.createThumbnailFromUri(mContext, imageUri, resizedMediaFile.getWidth(),
                        fileExtension, orientation);

                if (bytes == null) {
                    // We weren't able to resize the image, so we will upload the full size image with css to resize it
                    shouldUploadResizedVersion = false;
                    shouldAddImageWidthCSS = true;
                } else {
                    // Save temp image
                    String tempFilePath;
                    File resizedImageFile;
                    try {
                        resizedImageFile = File.createTempFile("wp-image-", fileExtension);
                        FileOutputStream out = new FileOutputStream(resizedImageFile);
                        out.write(bytes);
                        out.close();
                        tempFilePath = resizedImageFile.getPath();
                    } catch (IOException e) {
                        AppLog.w(T.POSTS, "failed to create image temp file");
                        mErrorMessage = mContext.getString(R.string.error_media_upload);
                        return null;
                    }

                    // upload resized picture
                    if (!TextUtils.isEmpty(tempFilePath)) {
                        resizedMediaFile.setFilePath(tempFilePath);
                        Map<String, Object> parameters = new HashMap<String, Object>();

                        parameters.put("name", fileName);
                        parameters.put("type", mimeType);
                        parameters.put("bits", resizedMediaFile);
                        parameters.put("overwrite", true);
                        resizedPictureURL = uploadImageFile(parameters, resizedMediaFile, mBlog);
                        if (resizedPictureURL == null) {
                            AppLog.w(T.POSTS, "failed to upload resized picture");
                            return null;
                        } else if (resizedImageFile.exists()) {
                            resizedImageFile.delete();
                        }
                    } else {
                        AppLog.w(T.POSTS, "failed to create resized picture");
                        mErrorMessage = mContext.getString(R.string.out_of_memory);
                        return null;
                    }
                }
            }

            String fullSizeUrl = null;
            // Upload the full size picture if "Original Size" is selected in settings,
            // or if 'link to full size' is checked.
            if (!shouldUploadResizedVersion || mBlog.isFullSizeImage()) {
                Map<String, Object> parameters = new HashMap<String, Object>();
                parameters.put("name", fileName);
                parameters.put("type", mimeType);
                parameters.put("bits", mediaFile);
                parameters.put("overwrite", true);

                fullSizeUrl = uploadImageFile(parameters, mediaFile, mBlog);
                if (fullSizeUrl == null) {
                    mErrorMessage = mContext.getString(R.string.error_media_upload);
                    return null;
                }
            }

            return mediaFile.getImageHtmlForUrls(fullSizeUrl, resizedPictureURL, shouldAddImageWidthCSS);
        }

        private String uploadVideo(MediaFile mediaFile) {
            // create temp file for media upload
            String tempFileName = "wp-" + System.currentTimeMillis();
            try {
                mContext.openFileOutput(tempFileName, Context.MODE_PRIVATE);
            } catch (FileNotFoundException e) {
                mErrorMessage = getResources().getString(R.string.file_error_create);
                return null;
            }

            if (mediaFile.getFilePath() == null) {
                mErrorMessage = mContext.getString(R.string.error_media_upload);
                return null;
            }

            Uri videoUri = Uri.parse(mediaFile.getFilePath());
            File videoFile = null;
            String mimeType = "", xRes = "", yRes = "";

            if (videoUri.toString().contains("content:")) {
                String[] projection = new String[]{Video.Media._ID, Video.Media.DATA, Video.Media.MIME_TYPE,
                        Video.Media.RESOLUTION};
                Cursor cur = mContext.getContentResolver().query(videoUri, projection, null, null, null);

                if (cur != null && cur.moveToFirst()) {
                    int dataColumn = cur.getColumnIndex(Video.Media.DATA);
                    int mimeTypeColumn = cur.getColumnIndex(Video.Media.MIME_TYPE);
                    int resolutionColumn = cur.getColumnIndex(Video.Media.RESOLUTION);

                    mediaFile = new MediaFile();

                    String thumbData = cur.getString(dataColumn);
                    mimeType = cur.getString(mimeTypeColumn);

                    videoFile = new File(thumbData);
                    mediaFile.setFilePath(videoFile.getPath());
                    String resolution = cur.getString(resolutionColumn);
                    if (resolution != null) {
                        String[] resolutions = resolution.split("x");
                        if (resolutions.length >= 2) {
                            xRes = resolutions[0];
                            yRes = resolutions[1];
                        }
                    } else {
                        // set the width of the video to the thumbnail width, else 640x480
                        if (!mBlog.getMaxImageWidth().equals("Original Size")) {
                            xRes = mBlog.getMaxImageWidth();
                            yRes = String.valueOf(Math.round(Integer.valueOf(mBlog.getMaxImageWidth()) * 0.75));
                        } else {
                            xRes = "640";
                            yRes = "480";
                        }
                    }
                }
            } else { // file is not in media library
                String filePath = videoUri.toString().replace("file://", "");
                mediaFile.setFilePath(filePath);
                videoFile = new File(filePath);
            }

            if (videoFile == null) {
                mErrorMessage = mContext.getResources().getString(R.string.error_media_upload);
                return null;
            }

            if (TextUtils.isEmpty(mimeType)) {
                mimeType = MediaUtils.getMediaFileMimeType(videoFile);
            }
            String videoName = MediaUtils.getMediaFileName(videoFile, mimeType);

            // try to upload the video
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("name", videoName);
            m.put("type", mimeType);
            m.put("bits", mediaFile);
            m.put("overwrite", true);

            Object[] params = {1, mBlog.getUsername(), mBlog.getPassword(), m};

            FeatureSet featureSet = synchronousGetFeatureSet();
            boolean selfHosted = WordPress.currentBlog != null && !WordPress.currentBlog.isDotcomFlag();
            boolean isVideoEnabled = selfHosted || (featureSet != null && mFeatureSet.isVideopressEnabled());
            if (isVideoEnabled) {
                File tempFile;
                try {
                    String fileExtension = MimeTypeMap.getFileExtensionFromUrl(videoName);
                    tempFile = createTempUploadFile(fileExtension);
                } catch (IOException e) {
                    mErrorMessage = getResources().getString(R.string.file_error_create);
                    return null;
                }

                Object result = uploadFileHelper(params, tempFile);
                Map<?, ?> resultMap = (HashMap<?, ?>) result;
                if (resultMap != null && resultMap.containsKey("url")) {
                    String resultURL = resultMap.get("url").toString();
                    if (resultMap.containsKey(MediaFile.VIDEOPRESS_SHORTCODE_ID)) {
                        resultURL = resultMap.get(MediaFile.VIDEOPRESS_SHORTCODE_ID).toString() + "\n";
                    } else {
                        resultURL = String.format(
                                "<video width=\"%s\" height=\"%s\" controls=\"controls\"><source src=\"%s\" type=\"%s\" /><a href=\"%s\">Click to view video</a>.</video>",
                                xRes, yRes, resultURL, mimeType, resultURL);
                    }

                    return resultURL;
                } else {
                    mErrorMessage = mContext.getResources().getString(R.string.error_media_upload);
                    return null;
                }
            } else {
                mErrorMessage = getString(R.string.media_no_video_message);
                mErrorUnavailableVideoPress = true;
                return null;
            }
        }


        private void setUploadPostErrorMessage(Exception e) {
            mErrorMessage = String.format(mContext.getResources().getText(R.string.error_upload).toString(),
                    mPost.isPage() ? mContext.getResources().getText(R.string.page).toString() :
                            mContext.getResources().getText(R.string.post).toString()) + " " + e.getMessage();
            mIsMediaError = false;
            AppLog.e(T.EDITOR, mErrorMessage, e);
        }

        private String uploadImageFile(Map<String, Object> pictureParams, MediaFile mf, Blog blog) {
            // create temporary upload file
            File tempFile;
            try {
                String fileExtension = MimeTypeMap.getFileExtensionFromUrl(mf.getFileName());
                tempFile = createTempUploadFile(fileExtension);
            } catch (IOException e) {
                mIsMediaError = true;
                mErrorMessage = mContext.getString(R.string.file_not_found);
                return null;
            }

            Object[] params = {1, blog.getUsername(), blog.getPassword(), pictureParams};
            Object result = uploadFileHelper(params, tempFile);
            if (result == null) {
                mIsMediaError = true;
                return null;
            }

            Map<?, ?> contentHash = (HashMap<?, ?>) result;
            String pictureURL = contentHash.get("url").toString();

            if (mf.isFeatured()) {
                try {
                    if (contentHash.get("id") != null) {
                        featuredImageID = Integer.parseInt(contentHash.get("id").toString());
                        if (!mf.isFeaturedInPost())
                            return "";
                    }
                } catch (NumberFormatException e) {
                    AppLog.e(T.POSTS, e);
                }
            }

            return pictureURL;
        }

        private Object uploadFileHelper(Object[] params, final File tempFile) {

            // Create listener for tracking upload progress in the notification
           if (mClient instanceof XMLRPCClient) {
                XMLRPCClient xmlrpcClient = (XMLRPCClient) mClient;
                xmlrpcClient.setOnBytesUploadedListener(new XMLRPCClient.OnBytesUploadedListener() {
                    @Override
                    public void onBytesUploaded(long uploadedBytes) {
                        if (tempFile.length() == 0) {
                            return;
                        }
                        float percentage = (uploadedBytes * 100) / tempFile.length();
                        mPostUploadNotifier.updateNotificationProgress(percentage);
                    }
                });
            }

            try {
                return mClient.call(ApiHelper.Methods.UPLOAD_FILE, params, tempFile);
            } catch (XMLRPCException e) {
                AppLog.e(T.API, e);
                mErrorMessage = mContext.getResources().getString(R.string.error_media_upload) + ": " + e.getMessage();
                return null;
            } catch (IOException e) {
                AppLog.e(T.API, e);
                mErrorMessage = mContext.getResources().getString(R.string.error_media_upload) + ": " + e.getMessage();
                return null;
            } catch (XmlPullParserException e) {
                AppLog.e(T.API, e);
                mErrorMessage = mContext.getResources().getString(R.string.error_media_upload) + ": " + e.getMessage();
                return null;
            } finally {
                // remove the temporary upload file now that we're done with it
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
            }
        }
    }

    private File createTempUploadFile(String fileExtension) throws IOException {
        return File.createTempFile("wp-", fileExtension, mContext.getCacheDir());
    }

    private class PostUploadNotifier {

        private final NotificationManager mNotificationManager;
        private final Builder mNotificationBuilder;

        private final int mNotificationId;
        private int mNotificationErrorId = 0;
        private int mTotalMediaItems;
        private int mCurrentMediaItem;
        private float mItemProgressSize;

        public PostUploadNotifier(Post post) {
            // add the uploader to the notification bar
            mNotificationManager = (NotificationManager) SystemServiceFactory.get(mContext,
                    Context.NOTIFICATION_SERVICE);

            mNotificationBuilder = new Builder(getApplicationContext());
            mNotificationBuilder.setSmallIcon(android.R.drawable.stat_sys_upload);
            mNotificationId = (new Random()).nextInt() + post.getLocalTableBlogId();
            startForeground(mNotificationId, mNotificationBuilder.build());
        }


        public void updateNotificationMessage(String title, String message) {
            if (title != null) {
                mNotificationBuilder.setContentTitle(title);
            }

            if (message != null) {
                mNotificationBuilder.setContentText(message);
            }

            mNotificationManager.notify(mNotificationId, mNotificationBuilder.build());
        }

        public void updateNotificationIcon(Bitmap icon) {
            if (icon != null) {
                mNotificationBuilder.setLargeIcon(icon);
            }

            mNotificationManager.notify(mNotificationId, mNotificationBuilder.build());
        }

        public void cancelNotification() {
            mNotificationManager.cancel(mNotificationId);
        }

        public void updateNotificationSuccess(Post post, Bitmap largeIcon) {
            AppLog.d(T.POSTS, "updateNotificationSuccess");

            // Get the sharableUrl
            String sharableUrl = WPMeShortlinks.getPostShortlink(post);
            if (sharableUrl == null && !TextUtils.isEmpty(post.getPermaLink())) {
                    // No short link or perma link - can't share, abort
                    sharableUrl = post.getPermaLink();
            }

            // Notification builder
            Builder notificationBuilder = new Builder(getApplicationContext());
            String notificationTitle = (String) (post.isPage() ? mContext.getResources().getText(R.string
                    .page_uploaded) : mContext.getResources().getText(R.string.post_uploaded));
            notificationBuilder.setSmallIcon(android.R.drawable.stat_sys_upload_done);
            if (largeIcon == null) {
                notificationBuilder.setLargeIcon(BitmapFactory.decodeResource(getApplicationContext().getResources(),
                        R.drawable.app_icon));
            } else {
                notificationBuilder.setLargeIcon(largeIcon);
            }
            notificationBuilder.setContentTitle(notificationTitle);
            notificationBuilder.setContentText(post.getTitle());

            // Tap notification intent (open the post list)
            Intent notificationIntent = new Intent(mContext, PostsListActivity.class);
            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            notificationIntent.putExtra(PostsListActivity.EXTRA_BLOG_LOCAL_ID, post.getLocalTableBlogId());
            notificationIntent.putExtra(PostsListActivity.EXTRA_VIEW_PAGES, post.isPage());
            PendingIntent pendingIntentPost = PendingIntent.getActivity(mContext, 0,
                    notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            notificationBuilder.setContentIntent(pendingIntentPost);

            // Share intent - started if the user tap the share link button - only if the link exist
            if (sharableUrl != null && post.getStatusEnum() == PostStatus.PUBLISHED) {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, sharableUrl);
                PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, share,
                        PendingIntent.FLAG_UPDATE_CURRENT);
                notificationBuilder.addAction(R.drawable.ic_share_white_24dp, getString(R.string.share_action),
                        pendingIntent);
            }
            mNotificationManager.notify(getNotificationIdForPost(post), notificationBuilder.build());
        }

        private int getNotificationIdForPost(Post post) {
            int remotePostId = StringUtils.stringToInt(post.getRemotePostId());
            // We can't use the local table post id here because it can change between first post (local draft) to
            // first edit (post pulled from the server)
            return post.getLocalTableBlogId() + remotePostId;
        }

        public void updateNotificationError(String mErrorMessage, boolean isMediaError, boolean isPage,
                                                boolean isVideoPressError) {
            AppLog.d(T.POSTS, "updateNotificationError: " + mErrorMessage);

            Builder notificationBuilder = new Builder(getApplicationContext());
            String postOrPage = (String) (isPage ? mContext.getResources().getText(R.string.page_id)
                    : mContext.getResources().getText(R.string.post_id));
            Intent notificationIntent = new Intent(mContext, PostsListActivity.class);
            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            notificationIntent.putExtra(PostsListActivity.EXTRA_VIEW_PAGES, isPage);
            notificationIntent.putExtra(PostsListActivity.EXTRA_ERROR_MSG, mErrorMessage);
            if (isVideoPressError) {
                notificationIntent.putExtra(PostsListActivity.EXTRA_ERROR_INFO_TITLE, getString(R.string.learn_more));
                notificationIntent.putExtra(PostsListActivity.EXTRA_ERROR_INFO_LINK, Constants.videoPressURL);
            }
            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0,
                    notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

            String errorText = mContext.getResources().getText(R.string.upload_failed).toString();
            if (isMediaError) {
                errorText = mContext.getResources().getText(R.string.media) + " "
                        + mContext.getResources().getText(R.string.error);
            }

            notificationBuilder.setSmallIcon(android.R.drawable.stat_notify_error);
            notificationBuilder.setContentTitle((isMediaError) ? errorText :
                    mContext.getResources().getText(R.string.upload_failed));
            notificationBuilder.setContentText((isMediaError) ? mErrorMessage : postOrPage + " " + errorText
                    + ": " + mErrorMessage);
            notificationBuilder.setContentIntent(pendingIntent);
            notificationBuilder.setAutoCancel(true);
            if (mNotificationErrorId == 0) {
                mNotificationErrorId = mNotificationId + (new Random()).nextInt();
            }
            mNotificationManager.notify(mNotificationErrorId, notificationBuilder.build());
        }

        public void updateNotificationProgress(float progress) {
            if (mTotalMediaItems == 0) {
                return;
            }

            // Simple way to show progress of entire post upload
            // Would be better if we could get total bytes for all media items.
            double currentChunkProgress = (mItemProgressSize * progress) / 100;

            if (mCurrentMediaItem > 1) {
                currentChunkProgress += mItemProgressSize * (mCurrentMediaItem - 1);
            }

            mNotificationBuilder.setProgress(100, (int)Math.ceil(currentChunkProgress), false);

            try {
                mNotificationManager.notify(mNotificationId, mNotificationBuilder.build());
            } catch (RuntimeException runtimeException) {
                CrashlyticsUtils.logException(runtimeException, CrashlyticsUtils.ExceptionType.SPECIFIC,
                        T.UTILS, "See issue #2858");
                AppLog.d(T.POSTS, "See issue #2858; notify failed with:" + runtimeException);
            }
        }

        public void setTotalMediaItems(int totalMediaItems) {
            if (totalMediaItems <= 0) {
                totalMediaItems = 1;
            }

            mTotalMediaItems = totalMediaItems;
            mItemProgressSize = 100.0f / mTotalMediaItems;
        }

        public void setCurrentMediaItem(int currentItem) {
            mCurrentMediaItem = currentItem;

            mNotificationBuilder.setContentText(String.format(getString(R.string.uploading_total), mCurrentMediaItem,
                    mTotalMediaItems));
        }
    }
}
