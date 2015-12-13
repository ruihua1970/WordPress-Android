package org.wordpress.android.models;

import android.database.Cursor;
import android.text.TextUtils;

import org.json.JSONObject;
import org.wordpress.android.util.JSONUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.UrlUtils;

public class ContactsContact {
    public long userId;
    public long blogId;
    private String userName;
    private String displayName;
    private String phoneNumber;
    private String url;
    private String profileUrl;
    private String avatarUrl;

    // isFollowed isn't read from json or stored in db - used by ReaderUserAdapter to mark followed users
    public transient boolean isFollowed;

    public static ContactsContact getContactFromCursor(Cursor c) {
        ContactsContact contact = new ContactsContact();

        //contact.userId = c.getLong(c.getColumnIndex("_ID"));
        //contact.blogId = c.getLong(c.getColumnIndex("blog_id"));
        //contact.setUserName(c.getString(c.getColumnIndex("user_name")));
        contact.setDisplayName(c.getString(c.getColumnIndex("DISPLAY_NAME")));
        contact.setPhoneNumber(c.getString(c.getColumnIndex("NUMBER")));
        //contact.setUrl(c.getString(c.getColumnIndex("url")));
        //contact.setProfileUrl(c.getString(c.getColumnIndex("profile_url")));
        //contact.setAvatarUrl(c.getString(c.getColumnIndex("avatar_url")));

        return contact;
    }


    public String getUserName() {
        return StringUtils.notNullStr(userName);
    }
    public void setUserName(String userName) {
        this.userName = StringUtils.notNullStr(userName);
    }

    public String getDisplayName() {
        return StringUtils.notNullStr(displayName);
    }

    public void setDisplayName(String displayName) {
        this.displayName = StringUtils.notNullStr(displayName);
    }

    public String getPhoneNumber()  {
        return StringUtils.notNullStr(phoneNumber);
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = StringUtils.notNullStr(phoneNumber);
    }



    public String getUrl() {
        return StringUtils.notNullStr(url);
    }
    public void setUrl(String url) {
        this.url = StringUtils.notNullStr(url);
    }

    public String getProfileUrl() {
        return StringUtils.notNullStr(profileUrl);
    }
    public void setProfileUrl(String profileUrl) {
        this.profileUrl = StringUtils.notNullStr(profileUrl);
    }

    public String getAvatarUrl() {
        return StringUtils.notNullStr(avatarUrl);
    }
    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = StringUtils.notNullStr(avatarUrl);
    }

    public boolean hasUrl() {
        return !TextUtils.isEmpty(url);
    }

}
