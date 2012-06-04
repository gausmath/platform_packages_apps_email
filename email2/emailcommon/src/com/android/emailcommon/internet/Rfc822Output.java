/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.emailcommon.internet;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Base64;
import android.util.Base64OutputStream;

import com.android.emailcommon.mail.Address;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.Message;

import org.apache.commons.io.IOUtils;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to output RFC 822 messages from provider email messages
 */
public class Rfc822Output {

    // In MIME, en_US-like date format should be used. In other words "MMM" should be encoded to
    // "Jan", not the other localized format like "Ene" (meaning January in locale es).
    private static final SimpleDateFormat DATE_FORMAT =
        new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);

    private static final String WHERE_NOT_SMART_FORWARD = "(" + Attachment.FLAGS + "&" +
        Attachment.FLAG_SMART_FORWARD + ")=0";

    /** A less-than-perfect pattern to pull out <body> content */
    private static final Pattern BODY_PATTERN = Pattern.compile(
                "(?:<\\s*body[^>]*>)(.*)(?:<\\s*/\\s*body\\s*>)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** Match group in {@code BODDY_PATTERN} for the body HTML */ 
    private static final int BODY_PATTERN_GROUP = 1;
    /** Index of the plain text version of the message body */
    private final static int INDEX_BODY_TEXT = 0;
    /** Index of the HTML version of the message body */
    private final static int INDEX_BODY_HTML = 1;
    /** Single digit [0-9] to ensure uniqueness of the MIME boundary */
    /*package*/ static byte sBoundaryDigit;

    /**
     * Returns just the content between the <body></body> tags. This is not perfect and breaks
     * with malformed HTML or if there happens to be special characters in the attributes of
     * the <body> tag (e.g. a '>' in a java script block).
     */
    /*package*/ static String getHtmlBody(String html) {
        Matcher match = BODY_PATTERN.matcher(html);
        if (match.find()) {
            return match.group(BODY_PATTERN_GROUP);    // Found body; return
        } else {
            return html;              // Body not found; return the full HTML and hope for the best
        }
    }

    /**
     * Gets both the plain text and HTML versions of the message body.
     */
    /*package*/ static String[] buildBodyText(Body body, int flags, boolean useSmartReply) {
        if (body == null) {
            return new String[2];
        }
        String[] messageBody = new String[] { body.mTextContent, body.mHtmlContent };
        if (useSmartReply && body.mQuotedTextStartPos > 0) {
            if (messageBody[0] != null) {
                messageBody[0] = messageBody[0].substring(0, body.mQuotedTextStartPos);
            } else if (messageBody[1] != null) {
                messageBody[1] = messageBody[1].substring(0, body.mQuotedTextStartPos);
            }
        }
        return messageBody;
    }

    /**
     * Write the entire message to an output stream.  This method provides buffering, so it is
     * not necessary to pass in a buffered output stream here.
     *
     * @param context system context for accessing the provider
     * @param messageId the message to write out
     * @param out the output stream to write the message to
     * @param useSmartReply whether or not quoted text is appended to a reply/forward
     */
    public static void writeTo(Context context, long messageId, OutputStream out,
            boolean useSmartReply, boolean sendBcc) throws IOException, MessagingException {
        Message message = Message.restoreMessageWithId(context, messageId);
        if (message == null) {
            // throw something?
            return;
        }

        OutputStream stream = new BufferedOutputStream(out, 1024);
        Writer writer = new OutputStreamWriter(stream);

        // Write the fixed headers.  Ordering is arbitrary (the legacy code iterated through a
        // hashmap here).

        String date = DATE_FORMAT.format(new Date(message.mTimeStamp));
        writeHeader(writer, "Date", date);

        writeEncodedHeader(writer, "Subject", message.mSubject);

        writeHeader(writer, "Message-ID", message.mMessageId);

        writeAddressHeader(writer, "From", message.mFrom);
        writeAddressHeader(writer, "To", message.mTo);
        writeAddressHeader(writer, "Cc", message.mCc);
        // Address fields.  Note that we skip bcc unless the sendBcc argument is true
        // SMTP should NOT send bcc headers, but EAS must send it!
        if (sendBcc) {
            writeAddressHeader(writer, "Bcc", message.mBcc);
        }
        writeAddressHeader(writer, "Reply-To", message.mReplyTo);
        writeHeader(writer, "MIME-Version", "1.0");

        // Analyze message and determine if we have multiparts
        Body body = Body.restoreBodyWithMessageId(context, message.mId);
        String[] bodyText = buildBodyText(body, message.mFlags, useSmartReply);

        Uri uri = ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, messageId);
        Cursor attachmentsCursor = context.getContentResolver().query(uri,
                Attachment.CONTENT_PROJECTION, WHERE_NOT_SMART_FORWARD, null, null);

        try {
            int attachmentCount = attachmentsCursor.getCount();
            boolean multipart = attachmentCount > 0;
            String multipartBoundary = null;
            String multipartType = "mixed";

            // Simplified case for no multipart - just emit text and be done.
            if (!multipart) {
                writeTextWithHeaders(writer, stream, bodyText);
            } else {
                // continue with multipart headers, then into multipart body
                multipartBoundary = getNextBoundary();

                // Move to the first attachment; this must succeed because multipart is true
                attachmentsCursor.moveToFirst();
                if (attachmentCount == 1) {
                    // If we've got one attachment and it's an ics "attachment", we want to send
                    // this as multipart/alternative instead of multipart/mixed
                    int flags = attachmentsCursor.getInt(Attachment.CONTENT_FLAGS_COLUMN);
                    if ((flags & Attachment.FLAG_ICS_ALTERNATIVE_PART) != 0) {
                        multipartType = "alternative";
                    }
                }

                writeHeader(writer, "Content-Type",
                        "multipart/" + multipartType + "; boundary=\"" + multipartBoundary + "\"");
                // Finish headers and prepare for body section(s)
                writer.write("\r\n");

                // first multipart element is the body
                if (bodyText[INDEX_BODY_TEXT] != null || bodyText[INDEX_BODY_HTML] != null) {
                    writeBoundary(writer, multipartBoundary, false);
                    writeTextWithHeaders(writer, stream, bodyText);
                }

                // Write out the attachments until we run out
                do {
                    writeBoundary(writer, multipartBoundary, false);
                    Attachment attachment =
                        Attachment.getContent(attachmentsCursor, Attachment.class);
                    writeOneAttachment(context, writer, stream, attachment);
                    writer.write("\r\n");
                } while (attachmentsCursor.moveToNext());

                // end of multipart section
                writeBoundary(writer, multipartBoundary, true);
            }
        } finally {
            attachmentsCursor.close();
        }

        writer.flush();
        out.flush();
    }

    /**
     * Write a single attachment and its payload
     */
    private static void writeOneAttachment(Context context, Writer writer, OutputStream out,
            Attachment attachment) throws IOException, MessagingException {
        writeHeader(writer, "Content-Type",
                attachment.mMimeType + ";\n name=\"" + attachment.mFileName + "\"");
        writeHeader(writer, "Content-Transfer-Encoding", "base64");
        // Most attachments (real files) will send Content-Disposition.  The suppression option
        // is used when sending calendar invites.
        if ((attachment.mFlags & Attachment.FLAG_ICS_ALTERNATIVE_PART) == 0) {
            writeHeader(writer, "Content-Disposition",
                    "attachment;"
                    + "\n filename=\"" + attachment.mFileName + "\";"
                    + "\n size=" + Long.toString(attachment.mSize));
        }
        if (attachment.mContentId != null) {
            writeHeader(writer, "Content-ID", attachment.mContentId);
        }
        writer.append("\r\n");

        // Set up input stream and write it out via base64
        InputStream inStream = null;
        try {
            // Use content, if provided; otherwise, use the contentUri
            if (attachment.mContentBytes != null) {
                inStream = new ByteArrayInputStream(attachment.mContentBytes);
            } else {
                // try to open the file
                Uri fileUri = Uri.parse(attachment.mContentUri);
                inStream = context.getContentResolver().openInputStream(fileUri);
            }
            // switch to output stream for base64 text output
            writer.flush();
            Base64OutputStream base64Out = new Base64OutputStream(
                out, Base64.CRLF | Base64.NO_CLOSE);
            // copy base64 data and close up
            IOUtils.copy(inStream, base64Out);
            base64Out.close();

            // The old Base64OutputStream wrote an extra CRLF after
            // the output.  It's not required by the base-64 spec; not
            // sure if it's required by RFC 822 or not.
            out.write('\r');
            out.write('\n');
            out.flush();
        }
        catch (FileNotFoundException fnfe) {
            // Ignore this - empty file is OK
        }
        catch (IOException ioe) {
            throw new MessagingException("Invalid attachment.", ioe);
        }
    }

    /**
     * Write a single header with no wrapping or encoding
     *
     * @param writer the output writer
     * @param name the header name
     * @param value the header value
     */
    private static void writeHeader(Writer writer, String name, String value) throws IOException {
        if (value != null && value.length() > 0) {
            writer.append(name);
            writer.append(": ");
            writer.append(value);
            writer.append("\r\n");
        }
    }

    /**
     * Write a single header using appropriate folding & encoding
     *
     * @param writer the output writer
     * @param name the header name
     * @param value the header value
     */
    private static void writeEncodedHeader(Writer writer, String name, String value)
            throws IOException {
        if (value != null && value.length() > 0) {
            writer.append(name);
            writer.append(": ");
            writer.append(MimeUtility.foldAndEncode2(value, name.length() + 2));
            writer.append("\r\n");
        }
    }

    /**
     * Unpack, encode, and fold address(es) into a header
     *
     * @param writer the output writer
     * @param name the header name
     * @param value the header value (a packed list of addresses)
     */
    private static void writeAddressHeader(Writer writer, String name, String value)
            throws IOException {
        if (value != null && value.length() > 0) {
            writer.append(name);
            writer.append(": ");
            writer.append(MimeUtility.fold(Address.packedToHeader(value), name.length() + 2));
            writer.append("\r\n");
        }
    }

    /**
     * Write a multipart boundary
     *
     * @param writer the output writer
     * @param boundary the boundary string
     * @param end false if inner boundary, true if final boundary
     */
    private static void writeBoundary(Writer writer, String boundary, boolean end)
            throws IOException {
        writer.append("--");
        writer.append(boundary);
        if (end) {
            writer.append("--");
        }
        writer.append("\r\n");
    }

    /**
     * Write the body text.
     *
     * Note this always uses base64, even when not required.  Slightly less efficient for
     * US-ASCII text, but handles all formats even when non-ascii chars are involved.  A small
     * optimization might be to prescan the string for safety and send raw if possible.
     *
     * @param writer the output writer
     * @param out the output stream inside the writer (used for byte[] access)
     * @param bodyText Plain text and HTML versions of the original text of the message
     */
    private static void writeTextWithHeaders(Writer writer, OutputStream out, String[] bodyText)
            throws IOException {
        boolean html = false;
        String text = bodyText[INDEX_BODY_TEXT];
        if (text == null) {
            text = bodyText[INDEX_BODY_HTML];
            html = true;
        }
        if (text == null) {
            writer.write("\r\n");       // a truly empty message
        } else {
            // first multipart element is the body
            String mimeType = "text/" + (html ? "html" : "plain");
            writeHeader(writer, "Content-Type", mimeType + "; charset=utf-8");
            writeHeader(writer, "Content-Transfer-Encoding", "base64");
            writer.write("\r\n");
            byte[] textBytes = text.getBytes("UTF-8");
            writer.flush();
            out.write(Base64.encode(textBytes, Base64.CRLF));
        }
    }

    /**
     * Returns a unique boundary string.
     */
    /*package*/ static String getNextBoundary() {
        StringBuilder boundary = new StringBuilder();
        boundary.append("--_com.android.email_").append(System.nanoTime());
        synchronized (Rfc822Output.class) {
            boundary = boundary.append(sBoundaryDigit);
            sBoundaryDigit = (byte)((sBoundaryDigit + 1) % 10);
        }
        return boundary.toString();
    }
}