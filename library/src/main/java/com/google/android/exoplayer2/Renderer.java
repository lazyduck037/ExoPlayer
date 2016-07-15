/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.exoplayer2;

import com.google.android.exoplayer2.ExoPlayer.ExoPlayerComponent;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.source.SampleStream;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.MediaClock;

import java.io.IOException;

/**
 * Renders media samples read from a {@link SampleStream}.
 * <p>
 * Internally, a renderer's lifecycle is managed by the owning {@link ExoPlayer}. The player will
 * transition its renderers through various states as the overall playback state changes. The valid
 * state transitions are shown below, annotated with the methods that are invoked during each
 * transition.
 * <p align="center"><img src="../../../../../images/trackrenderer_state.png"
 *     alt="Renderer state transitions"
 *     border="0"/></p>
 */
public abstract class Renderer implements ExoPlayerComponent, RendererCapabilities {

  /**
   * The renderer is disabled.
   */
  protected static final int STATE_DISABLED = 0;
  /**
   * The renderer is enabled but not started. A renderer in this state will typically hold any
   * resources that it requires for rendering (e.g. media decoders).
   */
  protected static final int STATE_ENABLED = 1;
  /**
   * The renderer is started. Calls to {@link #render(long, long)} will cause media to be rendered.
   */
  protected static final int STATE_STARTED = 2;

  private int index;
  private int state;
  private SampleStream stream;
  private long streamOffsetUs;
  private boolean readEndOfStream;
  private boolean streamIsFinal;

  public Renderer() {
    readEndOfStream = true;
  }

  /**
   * Sets the index of this renderer within the player.
   *
   * @param index The renderer index.
   */
  /* package */ final void setIndex(int index) {
    this.index = index;
  }

  /**
   * Returns the index of the renderer within the player.
   *
   * @return The index of the renderer within the player.
   */
  protected final int getIndex() {
    return index;
  }

  /**
   * If the renderer advances its own playback position then this method returns a corresponding
   * {@link MediaClock}. If provided, the player will use the returned {@link MediaClock} as its
   * source of time during playback. A player may have at most one renderer that returns a
   * {@link MediaClock} from this method.
   *
   * @return The {@link MediaClock} tracking the playback position of the renderer, or null.
   */
  protected MediaClock getMediaClock() {
    return null;
  }

  /**
   * Returns the current state of the renderer.
   *
   * @return The current state (one of the {@code STATE_*} constants).
   */
  protected final int getState() {
    return state;
  }

  /**
   * Enable the renderer to consume from the specified {@link SampleStream}.
   *
   * @param formats The enabled formats.
   * @param stream The {@link SampleStream} from which the renderer should consume.
   * @param positionUs The player's current position.
   * @param joining Whether this renderer is being enabled to join an ongoing playback.
   * @param offsetUs The offset to be added to timestamps of buffers read from {@code stream}
   *     before they are rendered.
   * @throws ExoPlaybackException If an error occurs.
   */
  /* package */ final void enable(Format[] formats, SampleStream stream, long positionUs,
      boolean joining, long offsetUs) throws ExoPlaybackException {
    Assertions.checkState(state == STATE_DISABLED);
    state = STATE_ENABLED;
    onEnabled(joining);
    replaceSampleStream(formats, stream, offsetUs);
    onReset(positionUs, joining);
  }

  /**
   * Called when the renderer is enabled.
   * <p>
   * The default implementation is a no-op.
   *
   * @param joining Whether this renderer is being enabled to join an ongoing playback.
   * @throws ExoPlaybackException If an error occurs.
   */
  protected void onEnabled(boolean joining) throws ExoPlaybackException {
    // Do nothing.
  }

  /**
   * Sets the {@link SampleStream} from which samples will be consumed.
   *
   * @param formats The enabled formats.
   * @param stream The {@link SampleStream} from which the renderer should consume.
   * @param offsetUs The offset to be added to timestamps of buffers read from {@code stream} before
   *     they are rendered.
   * @throws ExoPlaybackException If an error occurs.
   */
  /* package */ final void replaceSampleStream(Format[] formats, SampleStream stream, long offsetUs)
      throws ExoPlaybackException {
    Assertions.checkState(!streamIsFinal);
    this.stream = stream;
    readEndOfStream = false;
    streamOffsetUs = offsetUs;
    onStreamChanged(formats);
  }

  /**
   * Called when the renderer's stream has changed.
   * <p>
   * The default implementation is a no-op.
   *
   * @param formats The enabled formats.
   * @throws ExoPlaybackException Thrown if an error occurs.
   */
  protected void onStreamChanged(Format[] formats) throws ExoPlaybackException {
    // Do nothing.
  }

  /**
   * Called when a reset is encountered.
   *
   * @param positionUs The playback position in microseconds.
   * @throws ExoPlaybackException If an error occurs handling the reset.
   */
  /* package */ final void reset(long positionUs) throws ExoPlaybackException {
    streamIsFinal = false;
    onReset(positionUs, false);
  }

  /**
   * Invoked when a reset is encountered, and also when the renderer is enabled.
   * <p>
   * This method may be called when the renderer is in the following states:
   * {@link #STATE_ENABLED}, {@link #STATE_STARTED}.
   *
   * @param positionUs The playback position in microseconds.
   * @param joining Whether this renderer is being enabled to join an ongoing playback.
   * @throws ExoPlaybackException If an error occurs handling the reset.
   */
  protected void onReset(long positionUs, boolean joining) throws ExoPlaybackException {
    // Do nothing.
  }

  /**
   * Returns whether the renderer has read the current {@link SampleStream} to the end.
   */
  /* package */ final boolean hasReadStreamToEnd() {
    return readEndOfStream;
  }

  /**
   * Signals to the renderer that the current {@link SampleStream} will be the final one supplied
   * before it is next disabled or reset.
   */
  /* package */ final void setCurrentSampleStreamIsFinal() {
    streamIsFinal = true;
  }

  /**
   * Starts the renderer, meaning that calls to {@link #render(long, long)} will cause media to be
   * rendered.
   *
   * @throws ExoPlaybackException If an error occurs.
   */
  /* package */ final void start() throws ExoPlaybackException {
    Assertions.checkState(state == STATE_ENABLED);
    state = STATE_STARTED;
    onStarted();
  }

  /**
   * Called when the renderer is started.
   * <p>
   * The default implementation is a no-op.
   *
   * @throws ExoPlaybackException If an error occurs.
   */
  protected void onStarted() throws ExoPlaybackException {
    // Do nothing.
  }

  /**
   * Stops the renderer.
   *
   * @throws ExoPlaybackException If an error occurs.
   */
  /* package */ final void stop() throws ExoPlaybackException {
    Assertions.checkState(state == STATE_STARTED);
    state = STATE_ENABLED;
    onStopped();
  }

  /**
   * Called when the renderer is stopped.
   * <p>
   * The default implementation is a no-op.
   *
   * @throws ExoPlaybackException If an error occurs.
   */
  protected void onStopped() throws ExoPlaybackException {
    // Do nothing.
  }

  /**
   * Disable the renderer.
   */
  /* package */ final void disable() {
    Assertions.checkState(state == STATE_ENABLED);
    state = STATE_DISABLED;
    onDisabled();
    stream = null;
    streamIsFinal = false;
  }

  /**
   * Called when the renderer is disabled.
   * <p>
   * The default implementation is a no-op.
   */
  protected void onDisabled() {
    // Do nothing.
  }

  // Methods to be called by subclasses.

  /**
   * Throws an error that's preventing the renderer from reading from its {@link SampleStream}. Does
   * nothing if no such error exists.
   * <p>
   * This method may be called when the renderer is in the following states:
   * {@link #STATE_ENABLED}.
   *
   * @throws IOException An error that's preventing the renderer from making progress or buffering
   *     more data.
   */
  protected final void maybeThrowStreamError() throws IOException {
    stream.maybeThrowError();
  }

  /**
   * Reads from the enabled upstream source.
   *
   * @see SampleStream#readData(FormatHolder, DecoderInputBuffer)
   */
  protected final int readSource(FormatHolder formatHolder, DecoderInputBuffer buffer) {
    int result = stream.readData(formatHolder, buffer);
    if (result == C.RESULT_BUFFER_READ) {
      if (buffer.isEndOfStream()) {
        readEndOfStream = true;
        return streamIsFinal ? C.RESULT_BUFFER_READ : C.RESULT_NOTHING_READ;
      }
      buffer.timeUs += streamOffsetUs;
    }
    return result;
  }

  /**
   * Returns whether the upstream source is ready.
   *
   * @return True if the source is ready. False otherwise.
   */
  protected final boolean isSourceReady() {
    return readEndOfStream ? streamIsFinal : stream.isReady();
  }

  // Abstract methods.

  /**
   * Incrementally renders the {@link SampleStream}.
   * <p>
   * This method should return quickly, and should not block if the renderer is unable to make
   * useful progress.
   * <p>
   * This method may be called when the renderer is in the following states:
   * {@link #STATE_ENABLED}, {@link #STATE_STARTED}.
   *
   * @param positionUs The current media time in microseconds, measured at the start of the
   *     current iteration of the rendering loop.
   * @param elapsedRealtimeUs {@link android.os.SystemClock#elapsedRealtime()} in microseconds,
   *     measured at the start of the current iteration of the rendering loop.
   * @throws ExoPlaybackException If an error occurs.
   */
  protected abstract void render(long positionUs, long elapsedRealtimeUs)
      throws ExoPlaybackException;

  /**
   * Whether the renderer is able to immediately render media from the current position.
   * <p>
   * If the renderer is in the {@link #STATE_STARTED} state then returning true indicates that the
   * renderer has everything that it needs to continue playback. Returning false indicates that
   * the player should pause until the renderer is ready.
   * <p>
   * If the renderer is in the {@link #STATE_ENABLED} state then returning true indicates that the
   * renderer is ready for playback to be started. Returning false indicates that it is not.
   * <p>
   * This method may be called when the renderer is in the following states:
   * {@link #STATE_ENABLED}, {@link #STATE_STARTED}.
   *
   * @return True if the renderer is ready to render media. False otherwise.
   */
  protected abstract boolean isReady();

  /**
   * Whether the renderer is ready for the {@link ExoPlayer} instance to transition to
   * {@link ExoPlayer#STATE_ENDED}. The player will make this transition as soon as {@code true} is
   * returned by all of its {@link Renderer}s.
   * <p>
   * This method may be called when the renderer is in the following states:
   * {@link #STATE_ENABLED}, {@link #STATE_STARTED}.
   *
   * @return Whether the renderer is ready for the player to transition to the ended state.
   */
  protected abstract boolean isEnded();

  // RendererCapabilities implementation

  @Override
  public int supportsMixedMimeTypeAdaptation() throws ExoPlaybackException {
    return ADAPTIVE_NOT_SUPPORTED;
  }

  // ExoPlayerComponent implementation.

  @Override
  public void handleMessage(int what, Object object) throws ExoPlaybackException {
    // Do nothing.
  }

}