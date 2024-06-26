package app.revanced.integrations.youtube.patches.components;

import static app.revanced.integrations.youtube.utils.ByteTrieSearch.convertStringsToBytes;
import static app.revanced.integrations.youtube.utils.StringRef.str;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import app.revanced.integrations.youtube.settings.SettingsEnum;
import app.revanced.integrations.youtube.shared.NavigationBar;
import app.revanced.integrations.youtube.shared.NavigationBar.NavigationButton;
import app.revanced.integrations.youtube.shared.PlayerType;
import app.revanced.integrations.youtube.utils.ByteTrieSearch;
import app.revanced.integrations.youtube.utils.LogHelper;
import app.revanced.integrations.youtube.utils.ReVancedUtils;

/**
 * <pre>
 * Allows hiding home feed and search results based on keywords and/or channel names.
 *
 * Limitations:
 * - Searching for a keyword phrase will give no search results.
 *   This is because the buffer for each video contains the text the user searched for, and everything
 *   will be filtered away (even if that video title/channel does not contain any keywords).
 * - Filtering a channel name can still show Shorts from that channel in the search results.
 *   The most common Shorts layouts do not include the channel name, so they will not be filtered.
 * - Some layout component residue will remain, such as the video chapter previews for some search results.
 *   These components do not include the video title or channel name, and they
 *   appear outside the filtered components so they are not caught.
 * - Keywords are case sensitive, but some casing variation is manually added.
 *   (ie: "mr beast" automatically filters "Mr Beast" and "MR BEAST").
 */
@SuppressWarnings("unused")
final class KeywordContentFilter extends Filter {

    /**
     * Minimum keyword/phrase length to prevent excessively broad content filtering.
     */
    private static final int MINIMUM_KEYWORD_LENGTH = 3;

    /**
     * Strings found in the buffer for every video.
     * Full strings should be specified, as they are compared using {@link String#contains(CharSequence)}.
     * <p>
     * This list does not include every common buffer string, and this can be added/changed as needed.
     * Words must be entered with the exact casing as found in the buffer.
     */
    private static final String[] STRINGS_IN_EVERY_BUFFER = {
            // Video playback data.
            "https://i.ytimg.com/vi/", // Thumbnail url.
            "sddefault.jpg", // More video sizes exist, but for most devices only these 2 are used.
            "hqdefault.webp",
            "googlevideo.com/initplayback?source=youtube", // Video url.
            "ANDROID", // Video url parameter.
            // Video decoders.
            "OMX.ffmpeg.vp9.decoder",
            "OMX.Intel.sw_vd.vp9",
            "OMX.sprd.av1.decoder",
            "OMX.MTK.VIDEO.DECODER.SW.VP9",
            "c2.android.av1.decoder",
            "c2.mtk.sw.vp9.decoder",
            // User analytics.
            "https://ad.doubleclick.net/ddm/activity/",
            "DEVICE_ADVERTISER_ID_FOR_CONVERSION_TRACKING",
            // Litho components frequently found in the buffer that belong to the path filter items.
            "metadata.eml",
            "thumbnail.eml",
            "avatar.eml",
            "overflow_button.eml",
    };

    /**
     * Substrings that are always first in the path.
     */
    final StringFilterGroup startsWithFilter = new StringFilterGroup(
            SettingsEnum.HIDE_KEYWORD_CONTENT,
            "home_video_with_context.eml",
            "search_video_with_context.eml",
            "video_with_context.eml", // Subscription tab videos.
            "related_video_with_context.eml",
            "compact_video.eml",
            "inline_shorts",
            "shorts_video_cell",
            "shorts_pivot_item.eml"
    );

    /**
      Substrings that are never at the start of the path.
    */
    // Keywords are parsed on first call to isFiltered()
    // part of 'shorts_shelf_carousel.eml' and usually shown to tablet layout.
    final StringFilterGroup containsFilter = new StringFilterGroup(
            SettingsEnum.HIDE_KEYWORD_CONTENT,
            "modern_type_shelf_header_content.eml",
            "shorts_lockup_cell.eml", // Part of 'shorts_shelf_carousel.eml'
            "video_card.eml" // Shorts that appear in a horizontal shelf
    );

    final StringFilterGroup commentFilter = new StringFilterGroup(
            SettingsEnum.HIDE_KEYWORD_CONTENT_COMMENT,
            "comment.eml"
    );

    /**
     * The last value of {@link SettingsEnum#HIDE_KEYWORD_CONTENT_PHRASES}
     * parsed and loaded into {@link #bufferSearch}.
     * <p>
     * Field is intentionally compared using reference equality (and not the .equals() method).
     * <p>
     * Used to allow changing the keywords without restarting the app.
     */
    private volatile String lastKeywordPhrasesParsed;

    @GuardedBy("this")
    private volatile ByteTrieSearch bufferSearch;

    private static boolean hideKeywordSettingIsActive() {
        // Must check player type first, as search bar can be active behind the player.
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) {
            // For now, consider the under video results the same as the home feed.
            // Player active
            return SettingsEnum.HIDE_KEYWORD_CONTENT_HOME.getBoolean();
        }

        // Must check second, as search can be from any tab.
        if (NavigationBar.isSearchBarActive()) {
            // Search
            return SettingsEnum.HIDE_KEYWORD_CONTENT_SEARCH.getBoolean();
        }

        // Avoid checking navigation button status if all other settings are off.
        final boolean hideHome = SettingsEnum.HIDE_KEYWORD_CONTENT_HOME.getBoolean();
        final boolean hideSubscriptions = SettingsEnum.HIDE_SUBSCRIPTIONS_BUTTON.getBoolean();
        if (!hideHome && !hideSubscriptions) {
            return false;
        }

        NavigationButton selectedNavButton = NavigationButton.getSelectedNavigationButton();
        if (selectedNavButton == null) {
            return hideHome; // Unknown tab, treat the same as home.
        }

        if (selectedNavButton == NavigationButton.HOME) {
            return hideHome;
        }

        if (selectedNavButton == NavigationButton.SUBSCRIPTIONS) {
            return hideSubscriptions;
        }

        // User is in the Library or Notifications tab.
        return false;
    }

    /**
     * Change first letter of the first word to use title case.
     */
    private static String titleCaseFirstWordOnly(String sentence) {
        if (sentence.isEmpty()) {
            return sentence;
        }
        final int firstCodePoint = sentence.codePointAt(0);
        // In some non-English languages title case is different from uppercase.
        return new StringBuilder()
                .appendCodePoint(Character.toTitleCase(firstCodePoint))
                .append(sentence, Character.charCount(firstCodePoint), sentence.length())
                .toString();
    }

    /**
     * Uppercase the first letter of each word.
     */
    private static String capitalizeAllFirstLetters(String sentence) {
        if (sentence.isEmpty()) {
            return sentence;
        }
        final int delimiter = ' ';
        // Use code points and not characters to handle unicode surrogates.
        int[] codePoints = sentence.codePoints().toArray();
        boolean capitalizeNext = true;
        for (int i = 0, length = codePoints.length; i < length; i++) {
            final int codePoint = codePoints[i];
            if (codePoint == delimiter) {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                codePoints[i] = Character.toUpperCase(codePoint);
                capitalizeNext = false;
            }
        }
        return new String(codePoints, 0, codePoints.length);
    }

    /**
     * @return If the phrase will hide all videos. Not an exhaustive check.
     */
    private static boolean phrasesWillHideAllVideos(@NonNull String[] phrases) {
        for (String commonString : STRINGS_IN_EVERY_BUFFER) {
            if (ReVancedUtils.containsAny(commonString, phrases)) {
                return true;
            }
        }
        return false;
    }

    private synchronized void parseKeywords() { // Must be synchronized since Litho is multithreaded.
        String rawKeywords = SettingsEnum.HIDE_KEYWORD_CONTENT_PHRASES.getString();
        if (rawKeywords.equals(lastKeywordPhrasesParsed)) {
            LogHelper.printDebug(() -> "Using previously initialized search");
            return; // Another thread won the race, and search is already initialized.
        }

        ByteTrieSearch search = new ByteTrieSearch();
        String[] split = rawKeywords.split("\n");
        if (split.length != 0) {
            // Linked Set so log statement are more organized and easier to read.
            Set<String> keywords = new LinkedHashSet<>(10 * split.length);

            for (String phrase : split) {
                // Remove any trailing white space the user may have accidentally included.
                phrase = phrase.stripTrailing();
                if (phrase.isBlank()) continue;

                if (phrase.length() < MINIMUM_KEYWORD_LENGTH) {
                    // Do not reset the setting. Keep the invalid keywords so the user can fix the mistake.
                    ReVancedUtils.showToastLong(str("revanced_hide_keyword_toast_invalid_length", MINIMUM_KEYWORD_LENGTH, phrase));
                    continue;
                }

                // Add common casing that might appear.
                //
                // This could be simplified by adding case-insensitive search to the prefix search,
                // which is very simple to add to StringTrieSearch for Unicode and ByteTrieSearch for ASCII.
                //
                // But to support Unicode with ByteTrieSearch would require major changes because
                // UTF-8 characters can be different byte lengths, which does
                // not allow comparing two different byte arrays using simple plain array indexes.
                //
                // Instead, add all common case variations of the words.
                String[] phraseVariations = {
                        phrase,
                        phrase.toLowerCase(),
                        titleCaseFirstWordOnly(phrase),
                        capitalizeAllFirstLetters(phrase),
                        phrase.toUpperCase()
                };
                if (phrasesWillHideAllVideos(phraseVariations)) {
                    ReVancedUtils.showToastLong(str("revanced_hide_keyword_toast_invalid_common", phrase));
                    continue;
                }

                keywords.addAll(Arrays.asList(phraseVariations));
            }

            search.addPatterns(convertStringsToBytes(keywords.toArray(new String[0])));
            LogHelper.printDebug(() -> "Search using: (" + search.getEstimatedMemorySize() + " KB) keywords: " + keywords);
        }

        bufferSearch = search;
        lastKeywordPhrasesParsed = rawKeywords; // Must set last.
    }

    public KeywordContentFilter() {
        pathFilterGroupList.addAll(startsWithFilter, containsFilter, commentFilter);
    }

    @Override
    boolean isFiltered(String path, @Nullable String identifier, String allValue, byte[] protobufBufferArray,
                       FilterGroupList matchedList, FilterGroup matchedGroup, int matchedIndex) {
        if (matchedIndex != 0 && matchedGroup == startsWithFilter) {
            return false;
        }

        if (!hideKeywordSettingIsActive()) return false;

        if (!SettingsEnum.HIDE_KEYWORD_CONTENT_PHRASES.getString().equals(lastKeywordPhrasesParsed)) {
            // User changed the keywords.
            parseKeywords();
        }

        if (!bufferSearch.matches(protobufBufferArray)) {
            return false;
        }

        return super.isFiltered(path, identifier, allValue, protobufBufferArray, matchedList, matchedGroup, matchedIndex);
    }
}
