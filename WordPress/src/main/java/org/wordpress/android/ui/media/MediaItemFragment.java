package org.wordpress.android.ui.media;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewStub;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.toolbox.ImageLoader;
import com.android.volley.toolbox.NetworkImageView;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.WordPressDB;
import org.wordpress.android.models.Blog;
import org.wordpress.android.util.ImageUtils.BitmapWorkerCallback;
import org.wordpress.android.util.ImageUtils.BitmapWorkerTask;
import org.wordpress.android.util.MediaUtils;
import org.wordpress.android.util.StringUtils;

import java.util.ArrayList;

/**
 * A fragment display a media item's details.
 * Only appears on phone.
 */
public class MediaItemFragment extends Fragment {
    private static final String ARGS_MEDIA_ID = "media_id";

    public static final String TAG = MediaItemFragment.class.getName();

    private View mView;

    private ImageView mImageView;
    private TextView mTitleView;
    private TextView mCaptionView;
    private TextView mDescriptionView;
    private TextView mDateView;
    private TextView mFileNameView;
    private TextView mFileTypeView;
    private TextView mFileUrlView;
    private TextView mDimensionsView;
    private MediaItemFragmentCallback mCallback;
    private ImageLoader mImageLoader;
    private ProgressBar mProgressView;

    private boolean mIsLocal;

    public interface MediaItemFragmentCallback {
        public void onResume(Fragment fragment);
        public void onPause(Fragment fragment);
    }

    public static MediaItemFragment newInstance(String mediaId) {
        MediaItemFragment fragment = new MediaItemFragment();

        Bundle args = new Bundle();
        args.putString(ARGS_MEDIA_ID, mediaId);
        fragment.setArguments(args);

        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mImageLoader = MediaImageLoader.getInstance();
        setHasOptionsMenu(true);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        try {
            mCallback = (MediaItemFragmentCallback) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement MediaItemFragmentCallback");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mCallback.onResume(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        mCallback.onPause(this);
    }

    public String getMediaId() {
        if (getArguments() != null)
            return getArguments().getString(ARGS_MEDIA_ID);
        else
            return null;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.media_listitem_details, container, false);

        mTitleView = (TextView) mView.findViewById(R.id.media_listitem_details_title);
        mCaptionView = (TextView) mView.findViewById(R.id.media_listitem_details_caption);
        mDescriptionView = (TextView) mView.findViewById(R.id.media_listitem_details_description);
        mDateView = (TextView) mView.findViewById(R.id.media_listitem_details_date);
        mFileNameView = (TextView) mView.findViewById(R.id.media_listitem_details_file_name);
        mFileTypeView = (TextView) mView.findViewById(R.id.media_listitem_details_file_type);
        mFileUrlView = (TextView) mView.findViewById(R.id.media_listitem_details_file_url);
        mDimensionsView = (TextView) mView.findViewById(R.id.media_listitem_details_dimensions);
        mProgressView = (ProgressBar) mView.findViewById(R.id.media_listitem_details_progress);

        loadMedia(getMediaId());

        return mView;
    }

    /** Loads the first media item for the current blog from the database **/
    public void loadDefaultMedia() {
        loadMedia(null);
    }

    public void loadMedia(String mediaId) {
        Blog blog = WordPress.getCurrentBlog();

        if (blog != null) {
            String blogId = String.valueOf(blog.getLocalTableBlogId());

            Cursor cursor;

            // if the id is null, get the first media item in the database
            if (mediaId == null) {
                cursor = WordPress.wpDB.getFirstMediaFileForBlog(blogId);
            } else {
                cursor = WordPress.wpDB.getMediaFile(blogId, mediaId);
            }

            refreshViews(cursor);
            cursor.close();
        }
    }

    private void refreshViews(Cursor cursor) {
        if (!cursor.moveToFirst())
            return;

        // check whether or not to show the edit button
        String state = cursor.getString(cursor.getColumnIndex(WordPressDB.COLUMN_NAME_UPLOAD_STATE));
        mIsLocal = MediaUtils.isLocalFile(state);
        if (mIsLocal && getActivity() != null) {
            getActivity().invalidateOptionsMenu();
        }

        mTitleView.setText(cursor.getString(cursor.getColumnIndex(WordPressDB.COLUMN_NAME_TITLE)));

        String caption = cursor.getString(cursor.getColumnIndex(WordPressDB.COLUMN_NAME_CAPTION));
        if (caption == null || caption.length() == 0) {
            mCaptionView.setVisibility(View.GONE);
        } else {
            mCaptionView.setText(caption);
            mCaptionView.setVisibility(View.VISIBLE);
        }

        String desc = cursor.getString(cursor.getColumnIndex(WordPressDB.COLUMN_NAME_DESCRIPTION));
        if (desc == null || desc.length() == 0) {
            mDescriptionView.setVisibility(View.GONE);
        } else {
            mDescriptionView.setText(desc);
            mDescriptionView.setVisibility(View.VISIBLE);
        }

        String date = MediaUtils.getDate(cursor.getLong(cursor.getColumnIndex(WordPressDB.COLUMN_NAME_DATE_CREATED_GMT)));
        if (mIsLocal) {
            mDateView.setText(getResources().getString(R.string.media_details_added_on) + " " + date);
        } else {
            mDateView.setText(getResources().getString(R.string.media_details_uploaded_on) + " " + date);
        }

        String fileName = cursor.getString(cursor.getColumnIndex(WordPressDB.COLUMN_NAME_FILE_NAME));
        mFileNameView.setText(getResources().getString(R.string.media_details_file_name) + " " + fileName);

        // get the file extension from the fileURL
        String fileURL = cursor.getString(cursor.getColumnIndex(WordPressDB.COLUMN_NAME_FILE_URL));
        if (fileURL != null) {
            String fileType = fileURL.replaceAll(".*\\.(\\w+)$", "$1").toUpperCase();
            mFileTypeView.setText(getResources().getString(R.string.media_details_file_type) +
                    " " +  fileType);
            mFileTypeView.setVisibility(View.VISIBLE);

            // set the file URL
            mFileUrlView.setText(getResources().getString(R.string.media_details_file_url) + " " + fileURL);
            mFileUrlView.setVisibility(View.VISIBLE);
        } else {
            mFileTypeView.setVisibility(View.GONE);
            mFileUrlView.setVisibility(View.GONE);
        }

        String imageUri = cursor.getString(cursor.getColumnIndex(WordPressDB.COLUMN_NAME_FILE_URL));
        if (imageUri == null)
            imageUri = cursor.getString(cursor.getColumnIndex(WordPressDB.COLUMN_NAME_FILE_PATH));

        inflateImageView();

        // image and dimensions
        if (MediaUtils.isValidImage(imageUri)) {
            int width = cursor.getInt(cursor.getColumnIndex(WordPressDB.COLUMN_NAME_WIDTH));
            int height = cursor.getInt(cursor.getColumnIndex(WordPressDB.COLUMN_NAME_HEIGHT));

            float screenWidth;

            View parentView = (View) mImageView.getParent();

            //differentiating between tablet and phone
            if (this.isInLayout()) {
                screenWidth =  parentView.getMeasuredWidth();
            } else {
                screenWidth = getActivity().getResources().getDisplayMetrics().widthPixels;
            }
            float screenHeight = getActivity().getResources().getDisplayMetrics().heightPixels;

            if (width > 0 && height > 0) {
                String dimensions = width + "x" + height;
                mDimensionsView.setText(getResources().getString(R.string.media_details_dimensions) +
                        " " + dimensions);
                mDimensionsView.setVisibility(View.VISIBLE);
            } else {
                mDimensionsView.setVisibility(View.GONE);
            }

            if (width > screenWidth) {
                height = (int) (height / (width/screenWidth));
                width = (int) screenWidth;
            } else if (height > screenHeight) {
                width = (int) (width / (height/screenHeight));
                height = (int) screenHeight;
            }

            if (mIsLocal) {
                final String filePath = cursor.getString(cursor.getColumnIndex(WordPressDB.COLUMN_NAME_FILE_PATH));
                loadLocalImage(mImageView, filePath, width, height);
            } else {
                // Allow non-private wp.com and Jetpack blogs to use photon to get a higher res thumbnail
                if (WordPress.getCurrentBlog() != null && WordPress.getCurrentBlog().isPhotonCapable()){
                    String thumbnailURL = StringUtils.getPhotonUrl(imageUri, (int) screenWidth);
                    ((NetworkImageView) mImageView).setImageUrl(thumbnailURL, mImageLoader);
                } else {
                    ((NetworkImageView) mImageView).setImageUrl(imageUri + "?w=" + screenWidth, mImageLoader);
                }
            }
            mImageView.setVisibility(View.VISIBLE);

            mImageView.setLayoutParams(new RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, height));

        } else {
            mImageView.setVisibility(View.GONE);
            mProgressView.setVisibility(View.GONE);
            mDimensionsView.setVisibility(View.GONE);
        }
    }

    private void inflateImageView() {
        ViewStub viewStub = (ViewStub) mView.findViewById(R.id.media_listitem_details_stub);
        if (viewStub != null) {
            if (mIsLocal)
                viewStub.setLayoutResource(R.layout.media_grid_image_local);
            else
                viewStub.setLayoutResource(R.layout.media_grid_image_network);
            viewStub.inflate();
        }

        mImageView = (ImageView) mView.findViewById(R.id.media_listitem_details_image);

        // add a background color so something appears while image is downloaded
        mProgressView.setVisibility(View.VISIBLE);
        mImageView.setImageDrawable(new ColorDrawable(getResources().getColor(R.color.transparent)));
    }

    private synchronized void loadLocalImage(ImageView imageView, String filePath, int width, int height) {
        if (MediaUtils.isValidImage(filePath)) {
            imageView.setTag(filePath);

            Bitmap bitmap = WordPress.getBitmapCache().get(filePath);
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap);
            } else {
                BitmapWorkerTask task = new BitmapWorkerTask(imageView, width, height, new BitmapWorkerCallback() {
                    @Override
                    public void onBitmapReady(String path, ImageView imageView, Bitmap bitmap) {
                        imageView.setImageBitmap(bitmap);
                        WordPress.getBitmapCache().put(path, bitmap);
                    }
                });
                task.execute(filePath);
            }
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.media_details, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.menu_new_media).setVisible(false);
        menu.findItem(R.id.menu_search).setVisible(false);

        if (mIsLocal || !WordPressMediaUtils.isWordPressVersionWithMediaEditingCapabilities()) {
            menu.findItem(R.id.menu_edit_media).setVisible(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();

        if (itemId == R.id.menu_delete) {
            String blogId = String.valueOf(WordPress.getCurrentBlog().getLocalTableBlogId());
            boolean canDeleteMedia = WordPressMediaUtils.canDeleteMedia(blogId, getMediaId());
            if (!canDeleteMedia) {
                Toast.makeText(getActivity(), R.string.wait_until_upload_completes, Toast.LENGTH_LONG).show();
                return true;
            }

            Builder builder = new AlertDialog.Builder(getActivity()).setMessage(R.string.confirm_delete_media)
                    .setCancelable(true).setPositiveButton(
                            R.string.delete, new OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    ArrayList<String> ids = new ArrayList<String>(1);
                                    ids.add(getMediaId());
                                    if (getActivity() instanceof MediaBrowserActivity) {
                                        ((MediaBrowserActivity) getActivity()).deleteMedia(ids);
                                    }
                                }
                            }).setNegativeButton(R.string.cancel, null);
            AlertDialog dialog = builder.create();
            dialog.show();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
