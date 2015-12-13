package org.wordpress.android.models;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public class ContactsContactList extends ArrayList<ContactsContact> {


    /*
     * passed json is response from getting likes for a post
     */
    public static ContactsContactList fromJsonLikes(JSONObject json) {
        ContactsContactList contacts = new ContactsContactList();
        if (json==null)
            return contacts;

        JSONArray jsonLikes = json.optJSONArray("likes");
        if (jsonLikes!=null) {
            //for (int i=0; i < jsonLikes.length(); i++)
                //users.add(ReaderUser.fromJson(jsonLikes.optJSONObject(i)));
        }

        return contacts;
    }
}
