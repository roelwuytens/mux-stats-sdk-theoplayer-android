package com.mux.stats.sdk.muxstats.theoplayer;

import static android.os.SystemClock.elapsedRealtime;
import static com.mux.stats.sdk.muxstats.theoplayer.Util.secondsToMs;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.Nullable;

import com.mux.stats.sdk.core.CustomOptions;
import com.mux.stats.sdk.core.MuxSDKViewOrientation;
import com.mux.stats.sdk.core.events.EventBus;
import com.mux.stats.sdk.core.events.IEvent;
import com.mux.stats.sdk.core.events.InternalErrorEvent;
import com.mux.stats.sdk.core.events.playback.AdBreakEndEvent;
import com.mux.stats.sdk.core.events.playback.AdBreakStartEvent;
import com.mux.stats.sdk.core.events.playback.AdEndedEvent;
import com.mux.stats.sdk.core.events.playback.AdErrorEvent;
import com.mux.stats.sdk.core.events.playback.AdFirstQuartileEvent;
import com.mux.stats.sdk.core.events.playback.AdMidpointEvent;
import com.mux.stats.sdk.core.events.playback.AdPauseEvent;
import com.mux.stats.sdk.core.events.playback.AdPlayEvent;
import com.mux.stats.sdk.core.events.playback.AdPlayingEvent;
import com.mux.stats.sdk.core.events.playback.AdResponseEvent;
import com.mux.stats.sdk.core.events.playback.AdThirdQuartileEvent;
import com.mux.stats.sdk.core.events.playback.EndedEvent;
import com.mux.stats.sdk.core.events.playback.PauseEvent;
import com.mux.stats.sdk.core.events.playback.PlayEvent;
import com.mux.stats.sdk.core.events.playback.PlayingEvent;
import com.mux.stats.sdk.core.events.playback.RebufferEndEvent;
import com.mux.stats.sdk.core.events.playback.RebufferStartEvent;
import com.mux.stats.sdk.core.events.playback.RenditionChangeEvent;
import com.mux.stats.sdk.core.events.playback.SeekedEvent;
import com.mux.stats.sdk.core.events.playback.SeekingEvent;
import com.mux.stats.sdk.core.events.playback.TimeUpdateEvent;
import com.mux.stats.sdk.core.events.playback.VideoChangeEvent;
import com.mux.stats.sdk.core.model.CustomerData;
import com.mux.stats.sdk.core.model.CustomerVideoData;
import com.mux.stats.sdk.core.model.ViewData;
import com.mux.stats.sdk.core.util.MuxLogger;
import com.mux.stats.sdk.muxstats.IDevice;
import com.mux.stats.sdk.muxstats.INetworkRequest;
import com.mux.stats.sdk.muxstats.IPlayerListener;
import com.mux.stats.sdk.muxstats.LogPriority;
import com.mux.stats.sdk.muxstats.MuxErrorException;
import com.mux.stats.sdk.muxstats.MuxSDKViewPresentation;
import com.mux.stats.sdk.muxstats.MuxStats;
import com.theoplayer.android.api.THEOplayerView;
import com.theoplayer.android.api.ads.ima.GoogleImaAdEventType;
import com.theoplayer.android.api.error.THEOplayerException;
import com.theoplayer.android.api.event.EventListener;
import com.theoplayer.android.api.event.ads.AdsEventTypes;
import com.theoplayer.android.api.event.player.PlayerEventTypes;
import com.theoplayer.android.api.event.track.mediatrack.video.VideoTrackEventTypes;
import com.theoplayer.android.api.event.track.mediatrack.video.list.AddTrackEvent;
import com.theoplayer.android.api.event.track.mediatrack.video.list.VideoTrackListEventTypes;
import com.theoplayer.android.api.player.Player;
import com.theoplayer.android.api.player.ReadyState;
import com.theoplayer.android.api.player.track.mediatrack.quality.VideoQuality;
import com.theoplayer.android.api.source.SourceDescription;
import com.theoplayer.android.api.source.SourceType;
import com.theoplayer.android.api.source.TypedSource;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class MuxBaseSDKTheoPlayer extends EventBus implements IPlayerListener {
    public static final String TAG = "MuxBaseSDKTheoPlayer";

    protected PlayerState state;
    protected MuxStats muxStats;
    protected WeakReference<THEOplayerView> player;
    protected WeakReference<Context> contextRef;

    private final Lock releaseMutex = new ReentrantLock();

    protected static final int ERROR_UNKNOWN = -1;

    protected int sourceWidth;
    protected int sourceHeight;
    protected Integer sourceAdvertisedBitrate;
    protected Double sourceAdvertisedFramerate;
    protected double sourceDuration = -1D;
    protected boolean isPlaying;
    protected boolean sourceChanged;
    protected boolean inAdBreak;
    protected boolean inAdPlayback;
    protected ReadyState previousReadyState;
    /**
     * Set to true if the player is currently seeking. This also include all necessary network
     * buffering to start the playback from new position
     */
    boolean seekingInProgress;
    protected int numberOfEventsSent = 0;
    protected int numberOfPlayEventsSent = 0;

    protected double playbackPosition;

    public int streamType = -1;

    public enum PlayerState {
        BUFFERING, REBUFFERING, SEEKING, SEEKED, ERROR, PAUSED, PLAY, PLAYING, PLAYING_ADS,
        FINISHED_PLAYING_ADS, INIT, ENDED
    }

    MuxBaseSDKTheoPlayer(Context ctx, THEOplayerView playerView, String playerName,
                         CustomerData data, CustomOptions options,
                         INetworkRequest networkRequest) {
        super();

        this.player = new WeakReference<>(playerView);
        contextRef = new WeakReference<>(ctx);
        // MuxCore asserts non-null inputs
        options = options == null ? new CustomOptions() : options;

        MuxStats.setHostDevice(new MuxStatsSDKTHEOPlayer.MuxDevice(ctx, playerView.getVersion()));
        resetInternalStats();
        if (networkRequest == null) {
            MuxStats.setHostNetworkApi(new MuxNetworkRequests());
        } else {
            MuxStats.setHostNetworkApi(networkRequest);
        }
        muxStats = new MuxStats(this, playerName, data, options);
        addListener(muxStats);

        Player player = playerView.getPlayer();

        // TODO test this
        double aCurrentTime = player.getCurrentTime();
        if (aCurrentTime > 0) {
            // playback started before muxStats was initialized
            play();
            //buffering();
            playing();
        }

        // Nop idea how to get bitrate
        //                        sourceAdvertisedBitrate = vQuality.;
        EventListener<AddTrackEvent> handleAddTrackEvent = addTrackEvent -> {
            addTrackEvent.getTrack().addEventListener(
                    VideoTrackEventTypes.ACTIVEQUALITYCHANGEDEVENT,
                    activeQualityChangedEvent -> {
                        VideoQuality vQuality = activeQualityChangedEvent.getQuality();
                        if (vQuality != null) {
                            // Nop idea how to get bitrate
                            // sourceAdvertisedBitrate = vQuality.;
                            if (vQuality.getFrameRate() > 0) {
                                sourceAdvertisedFramerate = vQuality.getFrameRate();
                            }
                            sourceWidth = vQuality.getWidth();
                            sourceHeight = vQuality.getHeight();
                            RenditionChangeEvent event = new RenditionChangeEvent(null);
                            /*String msg = String.format(Locale.ROOT,
                                    "Rendition: %d x %d at %ffps", sourceWidth, sourceHeight, sourceAdvertisedFramerate
                            );*/
                            dispatch(event);
                        }
                    });
        };
        player.getVideoTracks()
                .addEventListener(VideoTrackListEventTypes.ADDTRACK, handleAddTrackEvent);
//      player.getPlayer().getVideoTracks().addEventListener(VideoTrackListEventTypes.TRACKLISTCHANGE,
//          trackListChangeEvent -> {
//            Log.d(TAG, "Tracklist Changed");
//            VideoQuality vQuality = trackListChangeEvent.getTrack().getActiveQuality();
//            if (vQuality != null) {
//              // Nop idea how to get bitrate
////                        sourceAdvertisedBitrate = vQuality.;
//              if (vQuality.getFrameRate() > 0) {
//                sourceAdvertisedFramerate = vQuality.getFrameRate();
//              }
//              sourceWidth = vQuality.getWidth();
//              sourceHeight = vQuality.getHeight();
//              RenditionChangeEvent event = new RenditionChangeEvent(null);
//              dispatch(event);
//            }
//          }
//      );

////////////////////////////////////////////////////////////////////////////////////////////////////
///////////////// Setup listeners //////////////////////////////////////////////////////////////////
        player.addEventListener(PlayerEventTypes.SOURCECHANGE, (sourceChangeEvent -> {
            this.sourceChanged = true;
        }));

        player.addEventListener(PlayerEventTypes.TIMEUPDATE,
                timeUpdateEvent -> {
                    if (!inAdBreak && getCurrentPlayer() != null) {
                        playbackPosition = timeUpdateEvent.getCurrentTime();
                        //Log.v("MuxBaseSDK", "Time Updated: " +playbackPosition);
                        dispatch(new TimeUpdateEvent(null));
                    }
                });

        player.addEventListener(PlayerEventTypes.DURATIONCHANGE,
                durationChangeEvent -> {
                    if (!inAdBreak && getCurrentPlayer() != null) {
                        sourceDuration = durationChangeEvent.getDuration();
                    }
                });

        player.addEventListener(PlayerEventTypes.PLAY, (playEvent -> {
            Player currentPlayer = getCurrentPlayer();
            if (currentPlayer != null && !currentPlayer.isAutoplay()
                    && numberOfPlayEventsSent == 0) {
                // This is first play event in autoplay = false sequence, ignore this
                return;
            }
            play();
        }));

        player.addEventListener(PlayerEventTypes.PLAYING, (playEvent -> {
            playing();
            Player currentPlayer = getCurrentPlayer();
            if (sourceChanged && currentPlayer != null) {
                sourceWidth = currentPlayer.getVideoWidth();
                sourceHeight = currentPlayer.getVideoHeight();
                dispatch(new VideoChangeEvent(null));
                sourceChanged = false;
            }
        }));

        player.addEventListener(PlayerEventTypes.READYSTATECHANGE, (stateChange -> {
            /*String logMsg = String.format(Locale.ROOT,
              "[tid %d] Ready State Changed: %s -> %s", Thread.currentThread().getId(), previousReadyState, stateChange.getReadyState()
            );*/
            ReadyState state = stateChange.getReadyState();
            // Leave this null-check. Despite the annotation this *can* be null during startup
            if (state != null) {
                // Playback starts when the state is HAVE_ENOUGH_DATA, and can continue in
                // HAVE_FUTURE_DATA, HAVE_ENOUGH_DATA.
                // (https://html.spec.whatwg.org/multipage/media.html#ready-states)
                // With HLS/DASH, HAVE_FUTURE_DATA indicates that the player is currently buffering
                // new segments in response to starvation, but it can still currently play
                if (previousReadyState != null
                        && (state.ordinal() < ReadyState.HAVE_FUTURE_DATA.ordinal()
                        && (state.ordinal() < previousReadyState.ordinal()))
                ) {
                    buffering();
                }
                previousReadyState = state;
            }
        }));

        player.addEventListener(PlayerEventTypes.PAUSE, (playEvent -> {
            pause();
        }));

        player.addEventListener(PlayerEventTypes.SEEKING, (playEvent -> {
            seeking();
        }));

        player.addEventListener(PlayerEventTypes.SEEKED, (playEvent -> {
            seeked();
        }));

        player.addEventListener(PlayerEventTypes.ENDED, (playEvent -> {
            state = PlayerState.ENDED;
            ended();
        }));

        player.addEventListener(PlayerEventTypes.ERROR, (errorEvent -> {
            THEOplayerException theoError = errorEvent.getErrorObject();
            internalError(new MuxErrorException(theoError.getCode().getId(), theoError.getMessage()));
        }));
////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////////////   Ads listeners  /////////////////////////////////////////////////////////////
        player.getAds().addEventListener(GoogleImaAdEventType.AD_ERROR, (event) -> {
            dispatch(new AdErrorEvent(null));
        });

        // SSAI/DAI Ads use AD_BREAK_STARTED/AD_BREAK_END
        player.getAds().addEventListener(GoogleImaAdEventType.AD_BREAK_STARTED, event -> {
            // todo - try to get some ids out of this (old version didn't either)
            handleAdBreakStarted("", "");
        });
        player.getAds().addEventListener(AdsEventTypes.AD_BREAK_END, event -> {
            handleAdBreakEnded();
        });
        // CSAI Ads use CONTENT_PAUSE_REQUESTED and CONTENT_RESUME_REQUESTED
        player.getAds().addEventListener(
                GoogleImaAdEventType.CONTENT_PAUSE_REQUESTED,
                googleImaAdEvent -> {
                    // todo - try to get some ids out of this (old version didn't either)
                    handleAdBreakStarted("", "");
                }
        );
        player.getAds().addEventListener(GoogleImaAdEventType.CONTENT_RESUME_REQUESTED,
                event -> {
                    handleAdBreakEnded();
                });
        player.getAds().addEventListener(GoogleImaAdEventType.STARTED, event -> {
            // Play listener is called before AD_BREAK_END event, this is a problem
            inAdPlayback = true;
            dispatch(new AdPlayingEvent(null));
        });
        player.getAds().addEventListener(GoogleImaAdEventType.COMPLETED, event -> {
            inAdPlayback = false;
            dispatch(new AdEndedEvent(null));
        });
        player.getAds().addEventListener(GoogleImaAdEventType.LOADED, event -> {
            dispatch(new AdResponseEvent(null));
        });
        player.getAds().addEventListener(GoogleImaAdEventType.PAUSED, event -> {
            // todo <em> these aren't called by theoplayer
            dispatch(new AdPauseEvent(null));
        });
        player.getAds().addEventListener(GoogleImaAdEventType.RESUMED, event -> {
            // todo <em> these aren't called by theoplayer
            dispatch(new AdPlayingEvent(null));
        });
        player.getAds().addEventListener(GoogleImaAdEventType.FIRST_QUARTILE, event -> {
            dispatch(new AdFirstQuartileEvent(null));
        });
        player.getAds().addEventListener(GoogleImaAdEventType.MIDPOINT, event -> {
            dispatch(new AdMidpointEvent(null));
        });
        player.getAds().addEventListener(GoogleImaAdEventType.THIRD_QUARTILE, event -> {
            dispatch(new AdThirdQuartileEvent(null));
        });
    }

    private void handleAdBreakStarted(String adId, String adCreativeId) {
        Player currentPlayer = getCurrentPlayer();
        if (currentPlayer == null) {
            return;
        }

        double playheadPos = currentPlayer.getCurrentTime();
        if (playheadPos > 0) {
            // Mid/Post-roll
            // Dispatch pause event because pause callback will not be called
            dispatch(new PauseEvent(null));
        } else {
            // Pre-roll
            // Dispatch play event because play callback will not be called for auto-play prerolls
            dispatch(new PlayEvent(null));
            // also dispatch Pause since this is an ad break starting
            dispatch(new PauseEvent(null));
        }
        // Record that we're in an ad break so we can supress standard play/playing/pause events
        AdBreakStartEvent adBreakEvent = new AdBreakStartEvent(null);
        // For everything but preroll ads, we need to simulate a pause event
        ViewData viewData = new ViewData();
        // TODO get these ids somehow
        viewData.setViewPrerollAdId(adId);
        viewData.setViewPrerollCreativeId(adCreativeId);
        adBreakEvent.setViewData(viewData);
        dispatch(adBreakEvent);
        // THEO has no specific event for this. The next should be STARTED which is adplaying
        dispatch(new AdPlayEvent(null));
    }

    private void handleAdBreakEnded() {
        inAdBreak = false;
        // Reset all of our state correctly for getting out of ads
        dispatch(new AdBreakEndEvent(null));
        // For everything but preroll ads, we need to simulate a play event to resume
        if (getCurrentPosition() == 0) {
            dispatch(new PlayEvent(null));
        }
    }

    /**
     * Reset internal counters for each new view.
     */
    private void resetInternalStats() {
        // detectMimeType = true;
        // numberOfPauseEventsSent = 0;
        isPlaying = false;
        state = PlayerState.INIT;
        numberOfPlayEventsSent = 0;
        numberOfEventsSent = 0;
    }

    public void release() {
        try {
            releaseMutex.lock();

            if (muxStats != null) {
                muxStats.release();
                muxStats = null;
            }
            player.clear();
        } finally {
            releaseMutex.unlock();
        }
    }

    protected void internalError(Exception error) {
        if (error instanceof MuxErrorException) {
            MuxErrorException muxError = (MuxErrorException) error;
            dispatch(new InternalErrorEvent(muxError.getCode(), muxError.getMessage()));
        } else {
            dispatch(new InternalErrorEvent(ERROR_UNKNOWN, error.getClass().getCanonicalName() + " - " + error.getMessage()));
        }
    }

    /*
     * This will be called by AdsImaSDKListener to set the player state to: PLAYING_ADS
     * and ADS_PLAYBACK_DONE accordingly
     */
    protected void setState(PlayerState newState) {
        state = newState;
    }

    // State Transitions
    public PlayerState getState() {
        return state;
    }

    public void orientationChange(MuxSDKViewOrientation orientation) {
        muxStats.orientationChange(orientation);
    }

    public void presentationChange(MuxSDKViewPresentation presentation) {
        muxStats.presentationChange(presentation);
    }

    // IPlayerListener
    @Override
    public long getCurrentPosition() {
        return secondsToMs(playbackPosition);
    }

    @Override
    public Integer getSourceWidth() {
        return sourceWidth;
    }

    @Override
    public Integer getSourceHeight() {
        return sourceHeight;
    }

    @Override
    public Integer getSourceAdvertisedBitrate() {
        return sourceAdvertisedBitrate;
    }

    @Override
    public Float getSourceAdvertisedFramerate() {
        if (sourceAdvertisedBitrate != null) {
            return sourceAdvertisedFramerate.floatValue();
        }
        return 0f;
    }

    @Override
    public Long getSourceDuration() {
        if (getCurrentPlayer() != null) {
            return (long) sourceDuration;
        }
        return -1L;
    }

    @Override
    public boolean isPaused() {
        return !isPlaying;
    }

    @Override
    public boolean isBuffering() {
        if (getCurrentPlayer() != null) {
            PlayerState state = getState();
            return state == PlayerState.BUFFERING || state == PlayerState.REBUFFERING;
        }
        return false;
    }

    @Override
    public int getPlayerViewWidth() {
        THEOplayerView currentPlayer = getCurrentPlayerView();
        if (currentPlayer != null) {
            return pxToDp(currentPlayer.getMeasuredWidth());
        }
        return 0;
    }

    @Override
    public int getPlayerViewHeight() {
        THEOplayerView currentPlayer = getCurrentPlayerView();
        if (currentPlayer != null) {
            return pxToDp(currentPlayer.getMeasuredHeight());
        }
        return 0;
    }

    /**
     * This method is not supported for THEOPlayer
     *
     * @return null in all cases
     */
    @Override
    public Long getPlayerProgramTime() {
        return null;
    }

    /**
     * This method is not supported for THEOPlayer
     *
     * @return null in all cases
     */
    @Override
    public Long getPlayerManifestNewestTime() {
        return null;
    }

    /**
     * This method is not supported for THEOPlayer
     *
     * @return null in all cases
     */
    @Override
    public Long getVideoHoldback() {
        return null;
    }

    /**
     * This method is not supported for THEOPlayer
     *
     * @return null in all cases
     */
    @Override
    public Long getVideoPartHoldback() {
        return null;
    }

    /**
     * This method is not supported for THEOPlayer
     *
     * @return null in all cases
     */
    @Override
    public Long getVideoPartTargetDuration() {
        return null;
    }

    /**
     * This method is not supported for THEOPlayer
     *
     * @return null in all cases
     */
    @Override
    public Long getVideoTargetDuration() {
        return null;
    }

    @Override
    public String getMimeType() {
        TypedSource firstSource = getFirstSource();
        if (firstSource != null) {
            SourceType type = firstSource.getType();
            if (type != null) {
                // @url https://docs.mux.com/guides/make-your-data-actionable-with-metadata
                return type.getMimeType();
            } else {
                return "";
            }
        } else {
            return null;
        }
    }

    // EventBus
    @Override
    public void dispatch(IEvent event) {
        // We also perform a lock to be sure we don't dispatch events when the player is being
        // released
        try {
            releaseMutex.lock();

            if (getCurrentPlayer() != null && muxStats != null) {
                numberOfEventsSent++;
                if (event instanceof PlayEvent) {
                    numberOfPlayEventsSent++;
                }
                super.dispatch(event);
            }
        } finally {
            releaseMutex.unlock();
        }
    }

    // Internal methods to change stats
    protected void buffering() {
        if (state == PlayerState.REBUFFERING
                || state == PlayerState.SEEKING
                || state == PlayerState.SEEKED) {
            // ignore
            return;
        }
        // If we are going from playing to buffering then this is rebuffer event
        if (state == PlayerState.PLAYING) {
            rebufferingStarted();
            return;
        }
        // This is initial buffering event before playback starts
        state = PlayerState.BUFFERING;
        dispatch(new TimeUpdateEvent(null));
    }

    protected void rebufferingStarted() {
        state = PlayerState.REBUFFERING;
        dispatch(new RebufferStartEvent(null));
    }

    protected void rebufferingEnded() {
        dispatch(new RebufferEndEvent(null));
    }

    protected void pause() {
        isPlaying = false;
        if (state == PlayerState.PAUSED || state == PlayerState.ENDED) {
            // ignore
            return;
        }
        if (state == PlayerState.REBUFFERING) {
            rebufferingEnded();
        }

        if (inAdBreak) {
            dispatch(new AdPauseEvent(null));
        } else {
            state = PlayerState.PAUSED;
            dispatch(new PauseEvent(null));
        }
    }

    protected void play() {
        isPlaying = true;
        if (inAdBreak) {
            if (inAdPlayback) {
                dispatch(new AdPlayEvent(null));
                // For some reason playing callback will not be fired.
                dispatch(new AdPlayingEvent(null));
            }
            return;
        }
        if (state == PlayerState.REBUFFERING
                || seekingInProgress
                || state == PlayerState.SEEKED) {
            // Ignore play event after rebuffering and Seeking
            return;
        }
        // Update the videoSource url
        Player currentPlayer = getCurrentPlayer();
        if (currentPlayer != null && muxStats != null) {
            String videoUrl = currentPlayer.getSrc();
            CustomerVideoData videoData = muxStats.getCustomerVideoData();
            videoData.setVideoSourceUrl(videoUrl);
            muxStats.updateCustomerData(null, videoData);
        }
        state = PlayerState.PLAY;
        dispatch(new PlayEvent(null));
    }

    protected void playing() {
        isPlaying = true;
        if (state == PlayerState.PLAYING || seekingInProgress) {
            // ignore
            return;
        }
        if (state == PlayerState.REBUFFERING) {
            rebufferingEnded();
        }
        if (state == PlayerState.PAUSED) {
            play();
        }
        state = PlayerState.PLAYING;
        if (inAdBreak) {
            if (inAdPlayback) {
                dispatch(new AdPlayingEvent(null));
            }
        } else {
            dispatch(new PlayingEvent(null));
        }
    }

    protected void seeking() {
        Player currentPlayer = getCurrentPlayer();
        if (currentPlayer == null) {
            return;
        }
        if ((state == PlayerState.INIT && currentPlayer.isAutoplay())
                || (isPaused() && numberOfPlayEventsSent < 2 && state == PlayerState.PLAY)
                || state == PlayerState.SEEKING
        ) {
            // This is the first seeking event triggered when player start from a position,
            // ignore this only if we are in autoplay mode.
            return;
        }
        if (state == PlayerState.PLAYING) {
            dispatch(new PauseEvent(null));
        }
        state = PlayerState.SEEKING;
        dispatch(new SeekingEvent(null));
        seekingInProgress = true;
    }

    protected void seeked() {
        if (state != PlayerState.SEEKING) {
            // Seeked can come only after seeking
            MuxLogger.d(TAG, "Seeked abborted, current state: " + state);
            return;
        }
        state = PlayerState.SEEKED;
        dispatch(new SeekedEvent(null));
        seekingInProgress = false;
        if (isPlaying) {
            playing();
        }
    }

    protected void ended() {
        dispatch(new PauseEvent(null));
        dispatch(new EndedEvent(null));
        state = PlayerState.ENDED;
        isPlaying = false;
    }

    static class MuxDevice implements IDevice {
        private static final String PLAYER_SOFTWARE = "THEOplayer";

        static final String CONNECTION_TYPE_CELLULAR = "cellular";
        static final String CONNECTION_TYPE_WIFI = "wifi";
        static final String CONNECTION_TYPE_WIRED = "wired";
        static final String CONNECTION_TYPE_OTHER = "other";

        protected WeakReference<Context> contextRef;
        private final String deviceId;
        private String appName = "";
        private String appVersion = "";
        private final String theoVersion;
        private String osFamily = "Android";

        MuxDevice(Context ctx, String theoVersion) {
            this.contextRef = new WeakReference<>(ctx);
            deviceId = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            this.theoVersion = theoVersion;
            try {
                PackageManager pm = ctx.getPackageManager();
                PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), 0);
                appName = pi.packageName;
                appVersion = pi.versionName;
                if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                    osFamily = "Android TV";
                }
            } catch (PackageManager.NameNotFoundException e) {
                MuxLogger.d(TAG, "could not get package info");
            }
        }

        @Override
        public String getHardwareArchitecture() {
            return Build.HARDWARE;
        }

        @Override
        public String getOSFamily() {
            return osFamily;
        }

        @Override
        public String getMuxOSFamily() {
            return null;
        }

        @Override
        public String getOSVersion() {
            return Build.VERSION.RELEASE + " (" + Build.VERSION.SDK_INT + ")";
        }

        @Override
        public String getMuxOSVersion() {
            return null;
        }

        @Override
        public String getDeviceName() {
            return null;
        }

        @Override
        public String getMuxDeviceName() {
            return null;
        }

        @Override
        public String getDeviceCategory() {
            return null;
        }

        @Override
        public String getMuxDeviceCategory() {
            return null;
        }

        @Override
        public String getManufacturer() {
            return Build.MANUFACTURER;
        }

        @Override
        public String getMuxManufacturer() {
            return null;
        }

        @Override
        public String getModelName() {
            return Build.MODEL;
        }

        @Override
        public String getMuxModelName() {
            return null;
        }

        @Override
        public String getPlayerVersion() {
            return this.theoVersion;
        }

        @Override
        public String getDeviceId() {
            return deviceId;
        }

        @Override
        public String getAppName() {
            return appName;
        }

        @Override
        public String getAppVersion() {
            return appVersion;
        }

        @Override
        public String getPluginName() {
            return BuildConfig.MUX_PLUGIN_NAME;
        }

        @Override
        public String getPluginVersion() {
            return BuildConfig.MUX_PLUGIN_VERSION;
        }

        @Override
        public String getPlayerSoftware() {
            return PLAYER_SOFTWARE;
        }

        @Override
        public String getNetworkConnectionType() {
            // Checking internet connectivity
            Context context = contextRef.get();
            if (context == null) {
                return null;
            }
            ConnectivityManager connectivityMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = null;
            if (connectivityMgr != null) {
                activeNetwork = connectivityMgr.getActiveNetworkInfo();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    NetworkCapabilities nc = connectivityMgr.getNetworkCapabilities(connectivityMgr.getActiveNetwork());
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        return CONNECTION_TYPE_WIRED;
                    } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return CONNECTION_TYPE_WIFI;
                    } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        return CONNECTION_TYPE_CELLULAR;
                    } else {
                        return CONNECTION_TYPE_OTHER;
                    }
                } else {
                    if (activeNetwork.getType() == ConnectivityManager.TYPE_ETHERNET) {
                        return CONNECTION_TYPE_WIRED;
                    } else if (activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) {
                        return CONNECTION_TYPE_WIFI;
                    } else if (activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE) {
                        return CONNECTION_TYPE_CELLULAR;
                    } else {
                        return CONNECTION_TYPE_OTHER;
                    }
                }
            }
            return null;
        }

        @Override
        public long getElapsedRealtime() {
            return elapsedRealtime();
        }

        @Override
        public void outputLog(LogPriority logPriority, String tag, String msg, Throwable throwable) {
            switch (logPriority) {
                case ERROR:
                    Log.e(tag, msg, throwable);
                    break;
                case WARN:
                    Log.w(tag, msg, throwable);
                    break;
                case INFO:
                    Log.i(tag, msg, throwable);
                    break;
                case DEBUG:
                    Log.d(tag, msg, throwable);
                    break;
                case VERBOSE:
                default: // fall-through
                    Log.v(tag, msg, throwable);
                    break;
            }
        }

        @Override
        public void outputLog(LogPriority logPriority, String tag, String msg) {
            switch (logPriority) {
                case ERROR:
                    Log.e(tag, msg);
                    break;
                case WARN:
                    Log.w(tag, msg);
                    break;
                case INFO:
                    Log.i(tag, msg);
                    break;
                case DEBUG:
                    Log.d(tag, msg);
                    break;
                case VERBOSE:
                default: // fall-through
                    Log.v(tag, msg);
                    break;
            }
        }

        @Override
        public void outputLog(String tag, String msg) {
            Log.v(tag, msg);
        }
    }

    private int pxToDp(int px) {
        Context context = contextRef.get();

        // Bail out if we don't have the context
        if (context == null) {
            MuxLogger.d(TAG, "Error retrieving Context for logical resolution, using physical");
            return px;
        }

        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        return (int) Math.ceil(px / displayMetrics.density);
    }

    @Nullable
    private Player getCurrentPlayer() {
        THEOplayerView playerView = getCurrentPlayerView();
        if (playerView != null) {
            return playerView.getPlayer();
        } else {
            return null;
        }
    }

    @Nullable
    private THEOplayerView getCurrentPlayerView() {
        WeakReference<THEOplayerView> player = this.player;
        if (player != null) {
            return player.get();
        } else {
            return null;
        }
    }

    @Nullable
    private TypedSource getFirstSource() {
        Player currentPlayer = getCurrentPlayer();
        if (currentPlayer == null) {
            return null;
        }

        SourceDescription source = currentPlayer.getSource();
        if (source == null) {
            return null;
        }

        // Normally non-null, but null check to be sure
        List<TypedSource> sources = source.getSources();
        if (sources == null || sources.isEmpty()) {
            return null;
        }

        return sources.get(0);
    }
}
