package org.wordpress.android.ui.contacts;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;

import org.wordpress.android.R;
import org.wordpress.android.datasets.ReaderCommentTable;
import org.wordpress.android.datasets.ReaderPostTable;
import org.wordpress.android.datasets.ReaderUserTable;
import org.wordpress.android.models.ContactsContact;
import org.wordpress.android.models.ContactsContactList;
import org.wordpress.android.models.ReaderUserList;
import org.wordpress.android.ui.contacts.adapters.ContactsAdapter;
import org.wordpress.android.ui.reader.ReaderConstants;
import org.wordpress.android.ui.reader.ReaderInterfaces;
import org.wordpress.android.ui.reader.adapters.ReaderUserAdapter;
import org.wordpress.android.ui.reader.utils.ReaderUtils;
import org.wordpress.android.ui.reader.views.ReaderRecyclerView;
import org.wordpress.android.util.DisplayUtils;
import org.wordpress.android.widgets.RecyclerItemDecoration;

/*
 * displays a list of contacts from device
 */
public class ContactsContactListActivity extends AppCompatActivity {

    private ReaderRecyclerView mRecyclerView;
    private ContactsAdapter mAdapter;
    private int mRestorePosition;
    private static Context mContext;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this.getApplicationContext();
        setContentView(R.layout.contacts_activity_contactlist);
        setTitle(null);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        getSupportActionBar().setDisplayShowTitleEnabled(true);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        if (savedInstanceState != null) {
            //mRestorePosition = savedInstanceState.getInt(ReaderConstants.KEY_RESTORE_POSITION);
        }

        int spacingHorizontal = 0;
        int spacingVertical = DisplayUtils.dpToPx(this, 1);
        mRecyclerView = (ReaderRecyclerView) findViewById(R.id.recycler_view);
        mRecyclerView.addItemDecoration(new RecyclerItemDecoration(spacingHorizontal, spacingVertical));


        loadContacts(mContext);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        int position = ((LinearLayoutManager) mRecyclerView.getLayoutManager()).findFirstVisibleItemPosition();
        if (position > 0) {
            //outState.putInt(ReaderConstants.KEY_RESTORE_POSITION, position);
        }
        super.onSaveInstanceState(outState);
    }

    private ContactsAdapter getAdapter() {
        if (mAdapter == null) {
            mAdapter = new ContactsAdapter(this);
            mAdapter.setDataLoadedListener(new ReaderInterfaces.DataLoadedListener() {
                @Override
                public void onDataLoaded(boolean isEmpty) {
                    if (!isEmpty && mRestorePosition > 0) {
                        mRecyclerView.scrollToPosition(mRestorePosition);
                    }
                    mRestorePosition = 0;
                }
            });
            mRecyclerView.setAdapter(mAdapter);
        }
        return mAdapter;
    }

    private void loadContacts(Context context) {

        new Thread() {
            @Override
            public void run() {
                //final String title = getTitleString(blogId, postId, commentId);
                final ContactsContactList contacts;
                contacts = new ContactsContactList();

                String phoneNumber = null;
                Uri CONTENT_URI = ContactsContract.Contacts.CONTENT_URI;

                String _ID = ContactsContract.Contacts._ID;
                String DISPLAY_NAME =  ContactsContract.Contacts.DISPLAY_NAME_PRIMARY;;
                String HAS_PHONE_NUMBER = ContactsContract.Contacts.HAS_PHONE_NUMBER;
                Uri PhoneCONTENT_URI = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
                String Phone_CONTACT_ID = ContactsContract.CommonDataKinds.Phone.CONTACT_ID;
                String NUMBER = ContactsContract.CommonDataKinds.Phone.NUMBER;


                //ContentResolver contentResolver = getContentResolver();
                Cursor cursor = mContext.getContentResolver().query(CONTENT_URI, null, null, null, null);
                if (cursor.getCount() > 0) {
                    int x=cursor.getCount();
                   Log.d("","wwww"+x);
                    //cursor.toString();
                    while (cursor.moveToNext()) {
                        ContactsContact contact = new ContactsContact();
                        String contact_id = cursor.getString(cursor.getColumnIndex( _ID ));
                        //String name = cursor.getString(cursor.getColumnIndex( DISPLAY_NAME ));
                        String name = cursor.getString(cursor.getColumnIndex( DISPLAY_NAME ));
                        //String phoneNumber = cursor.getString(cursor.getColumnIndex( NUMBER ));
                        int hasPhoneNumber =Integer.parseInt(cursor.getString(cursor.getColumnIndex( HAS_PHONE_NUMBER )));
                        if (hasPhoneNumber > 0) {
                        // Query and loop for every phone number of the contact
                            Cursor phoneCursor = mContext.getContentResolver().query(PhoneCONTENT_URI, null, Phone_CONTACT_ID + " = ?", new String[]{contact_id}, null);

                            while (phoneCursor.moveToNext()) {
                                phoneNumber = phoneCursor.getString(phoneCursor.getColumnIndex(NUMBER));
                            }
                            phoneCursor.close();
                        }
                        contact.setDisplayName(name);
                        contact.setPhoneNumber(phoneNumber);
                        //contacts.add(ContactsContact.getContactFromCursor(cursor));
                        contacts.add(contact);
                        //users.add(getUserFromCursor(c));
                    }

                }


                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) {
                            //setTitle(title);
                            getAdapter().setContacts(contacts);
                        }
                    }
                });
            }
        }.start();
    }

    private String getTitleString(final long blogId,
                                  final long postId,
                                  final long commentId) {
        final int numLikes;
        final boolean isLikedByCurrentUser;
        if (commentId == 0) {
            numLikes = ReaderPostTable.getNumLikesForPost(blogId, postId);
            isLikedByCurrentUser = ReaderPostTable.isPostLikedByCurrentUser(blogId, postId);
        } else {
            numLikes = ReaderCommentTable.getNumLikesForComment(blogId, postId, commentId);
            isLikedByCurrentUser = ReaderCommentTable.isCommentLikedByCurrentUser(blogId, postId, commentId);
        }
        return ReaderUtils.getLongLikeLabelText(this, numLikes, isLikedByCurrentUser);
    }

}
