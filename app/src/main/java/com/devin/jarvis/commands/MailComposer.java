package com.devin.jarvis.commands;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Voice-mail composer. We don't read mail in v1 (IMAP requires the user to
 * generate an app password — too hairy for voice flow). Sending is one
 * Intent.ACTION_SENDTO away — opens Gmail/Outlook/etc. with the body
 * pre-filled, the user just taps Send.
 *
 * Optional READ_CONTACTS lets the user say "напиши Олегу" instead of an
 * address.
 */
public class MailComposer {

    private final Context ctx;

    public MailComposer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean canReadContacts() {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static class ContactMatch {
        public final String displayName;
        public final String email;
        public ContactMatch(String n, String e) { this.displayName = n; this.email = e; }
    }

    /** Resolves a name like "Олегу" to the first contact email. Returns null on failure. */
    public ContactMatch findContactEmail(String nameLike) {
        if (!canReadContacts() || nameLike == null) return null;
        // Russian dative is awkward — strip common endings to improve match.
        String stem = stemRu(nameLike.trim());
        if (stem.isEmpty()) return null;
        ContentResolver cr = ctx.getContentResolver();
        String[] projection = new String[]{
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
        };
        // Match by display name OR by raw email containing the stem.
        String selection = ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY + " LIKE ? OR "
                + ContactsContract.CommonDataKinds.Email.ADDRESS + " LIKE ?";
        String[] args = new String[]{ "%" + stem + "%", "%" + stem.toLowerCase() + "%" };
        try (Cursor c = cr.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                projection, selection, args, null)) {
            if (c == null) return null;
            if (c.moveToFirst()) {
                String dn = c.getString(0);
                String em = c.getString(1);
                if (em != null && !em.isEmpty()) return new ContactMatch(dn, em);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Returns at most {@code limit} contact-email matches. */
    public List<ContactMatch> searchContacts(String nameLike, int limit) {
        List<ContactMatch> out = new ArrayList<>();
        if (!canReadContacts() || nameLike == null) return out;
        String stem = stemRu(nameLike.trim());
        if (stem.isEmpty()) return out;
        ContentResolver cr = ctx.getContentResolver();
        String[] projection = new String[]{
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
        };
        String selection = ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY + " LIKE ?";
        String[] args = new String[]{ "%" + stem + "%" };
        try (Cursor c = cr.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                projection, selection, args, null)) {
            if (c == null) return out;
            int n = 0;
            while (c.moveToNext() && n < limit) {
                String em = c.getString(1);
                if (em == null || em.isEmpty()) continue;
                out.add(new ContactMatch(c.getString(0), em));
                n++;
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** Opens an email compose UI with the fields pre-filled. */
    public boolean compose(String to, String subject, String body) {
        try {
            Uri.Builder b = new Uri.Builder().scheme("mailto");
            // Gmail's ACTION_SENDTO+mailto handler is the most reliable across
            // OEMs; it opens the user's default mail client.
            String addr = to == null ? "" : to.trim();
            String mailto = "mailto:" + Uri.encode(addr);
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse(mailto));
            if (subject != null && !subject.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, subject);
            if (body != null && !body.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, body);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Strips common Russian noun-case endings so contact lookup with "Олегу"
     * still finds an entry stored as "Олег". Idempotent for already-base
     * forms. Cheap heuristic — not a full morphology engine.
     */
    static String stemRu(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.length() <= 3) return t;
        String[] suffixes = {"ому", "ему", "ого", "его", "ыми", "ими",
                "ой", "ей", "ия", "ие", "ия", "ам", "ям", "ах", "ях",
                "у", "ю", "е", "и", "а", "я", "ы"};
        for (String suf : suffixes) {
            if (t.endsWith(suf) && t.length() - suf.length() >= 3) {
                return t.substring(0, t.length() - suf.length());
            }
        }
        return t;
    }
}
