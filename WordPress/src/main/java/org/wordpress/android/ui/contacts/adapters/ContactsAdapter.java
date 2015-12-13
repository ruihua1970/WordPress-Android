package org.wordpress.android.ui.contacts.adapters;

import android.content.ContentUris;
import android.content.Context;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.wordpress.android.R;
import org.wordpress.android.models.ContactsContact;
import org.wordpress.android.models.ContactsContactList;
import org.wordpress.android.ui.reader.ReaderInterfaces;
import org.wordpress.android.util.GravatarUtils;
import org.wordpress.android.widgets.WPNetworkImageView;

/**
 * Created by do on 2015/10/26.
 */
public class ContactsAdapter extends RecyclerView.Adapter<ContactsAdapter.ContactsViewHolder> {
    private ContactsContactList mContacts = new ContactsContactList();
    private ReaderInterfaces.DataLoadedListener mDataLoadedListener;
    private final int mAvatarSz;
    private Context context;

    public ContactsAdapter(Context context) {
        super();
        mAvatarSz = context.getResources().getDimensionPixelSize(R.dimen.avatar_sz_small);
        this.context = context;
    }

    public void setDataLoadedListener(ReaderInterfaces.DataLoadedListener listener) {
        mDataLoadedListener = listener;
    }

    @Override
    public int getItemCount() {
        return mContacts.size();
    }

    boolean isEmpty() {
        return (getItemCount() == 0);
    }

    @Override
    public ContactsViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.contacts_listitem_contact, parent, false);
        return new ContactsViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ContactsViewHolder holder, int position) {
        final ContactsContact contact = mContacts.get(position);

        holder.txtName.setText(contact.getDisplayName());
        holder.txtPhoneNumber.setText(contact.getPhoneNumber());
        if (contact.hasUrl()) {
            //holder.txtUrl.setVisibility(View.VISIBLE);
            //holder.txtUrl.setText(user.getUrlDomain());
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    /*if (user.hasBlogId()) {
                        ReaderActivityLauncher.showReaderBlogPreview(
                                v.getContext(),
                                user.blogId);
                    }*/
                }
            });
        } else {
            holder.txtUrl.setVisibility(View.GONE);
            holder.btnInvite.setText(R.string.contacts_invite_contact);
            holder.btnInvite.setVisibility(View.VISIBLE);
            //holder.itemView.setOnClickListener(null);
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Uri sms_uri= Uri.parse("smsto:"+contact.getPhoneNumber());//设置号码
                    Intent sms_intent = new Intent(Intent.ACTION_SENDTO,sms_uri);//调用发短信Action
                    sms_intent.putExtra("sms_body", "HelloWorld");//用Intent设置短信内容
                    context.startActivity(sms_intent);
                }
            });
        }

        holder.imgAvatar.setImageUrl(contact.getAvatarUrl(), WPNetworkImageView.ImageType.AVATAR);
    }

    @Override
    public long getItemId(int position) {
        return mContacts.get(position).userId;
    }

    class ContactsViewHolder extends RecyclerView.ViewHolder{
        private final TextView txtName;
        private final TextView txtPhoneNumber;
        private final TextView txtUrl;
        private final WPNetworkImageView imgAvatar;
        private final TextView btnInvite;

        public ContactsViewHolder(View view) {
            super(view);
            txtName = (TextView) view.findViewById(R.id.text_name);
            txtPhoneNumber = (TextView) view.findViewById(R.id.phone_number);
            txtUrl = (TextView) view.findViewById(R.id.text_url);
            imgAvatar = (WPNetworkImageView) view.findViewById(R.id.image_avatar);
            btnInvite = (TextView)view.findViewById(R.id.button_invite_action);
        }
    }

    private void clear() {
        if (mContacts.size() > 0) {
            mContacts.clear();
            notifyDataSetChanged();
        }
    }

    public void setContacts(final ContactsContactList contacts) {
        if (contacts == null || contacts.size() == 0) {
            clear();
            return;
        }

        mContacts = (ContactsContactList) contacts.clone();
        final Handler handler = new Handler();

        new Thread() {
            @Override
            public void run() {
                // flag followed users, set avatar urls for use with photon, and pre-load
                // user domains so we can avoid having to do this for each user when getView()
                // is called
                //ReaderUrlList followedBlogUrls = ReaderBlogTable.getFollowedBlogUrls();
                for (ContactsContact contact: mContacts) {
                    //contact.isFollowed = user.hasUrl() && followedBlogUrls.contains(user.getUrl());
                    contact.setAvatarUrl(GravatarUtils.fixGravatarUrl(contact.getAvatarUrl(), mAvatarSz));
                    //contact.getUrlDomain();
                }

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyDataSetChanged();
                        if (mDataLoadedListener != null) {
                            mDataLoadedListener.onDataLoaded(isEmpty());
                        }
                    }
                });
            }
        }.start();
    }
}