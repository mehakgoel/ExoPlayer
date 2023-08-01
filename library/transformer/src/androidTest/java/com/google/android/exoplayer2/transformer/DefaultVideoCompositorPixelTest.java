/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.transformer;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.android.exoplayer2.testutil.BitmapPixelTestUtil.MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE_DIFFERENT_DEVICE;
import static com.google.android.exoplayer2.testutil.BitmapPixelTestUtil.maybeSaveTestBitmap;
import static com.google.android.exoplayer2.testutil.BitmapPixelTestUtil.readBitmap;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.graphics.Bitmap;
import android.opengl.EGLContext;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.effect.DefaultGlObjectsProvider;
import com.google.android.exoplayer2.effect.DefaultVideoCompositor;
import com.google.android.exoplayer2.effect.DefaultVideoFrameProcessor;
import com.google.android.exoplayer2.effect.RgbFilter;
import com.google.android.exoplayer2.effect.ScaleAndRotateTransformation;
import com.google.android.exoplayer2.effect.VideoCompositor;
import com.google.android.exoplayer2.testutil.BitmapPixelTestUtil;
import com.google.android.exoplayer2.testutil.TextureBitmapReader;
import com.google.android.exoplayer2.testutil.VideoFrameProcessorTestRunner;
import com.google.android.exoplayer2.util.Effect;
import com.google.android.exoplayer2.util.GlObjectsProvider;
import com.google.android.exoplayer2.util.GlTextureInfo;
import com.google.android.exoplayer2.util.GlUtil;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.util.VideoFrameProcessingException;
import com.google.android.exoplayer2.video.ColorInfo;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Pixel test for {@link DefaultVideoCompositor} compositing 2 input frames into 1 output frame. */
@RunWith(Parameterized.class)
public final class DefaultVideoCompositorPixelTest {

  private static final String ORIGINAL_PNG_ASSET_PATH = "media/bitmap/input_images/media3test.png";
  private static final String GRAYSCALE_PNG_ASSET_PATH =
      "media/bitmap/sample_mp4_first_frame/electrical_colors/grayscale_media3test.png";
  private static final String ROTATE180_PNG_ASSET_PATH =
      "media/bitmap/sample_mp4_first_frame/electrical_colors/rotate180_media3test.png";
  private static final String GRAYSCALE_AND_ROTATE180_COMPOSITE_PNG_ASSET_PATH =
      "media/bitmap/sample_mp4_first_frame/electrical_colors/grayscaleAndRotate180Composite.png";

  @Parameterized.Parameters(name = "useSharedExecutor={0}")
  public static ImmutableList<Boolean> useSharedExecutor() {
    return ImmutableList.of(true, false);
  }

  @Parameterized.Parameter public boolean useSharedExecutor;
  @Rule public TestName testName = new TestName();

  private @MonotonicNonNull VideoCompositorTestRunner compositorTestRunner;

  @After
  public void tearDown() {
    if (compositorTestRunner != null) {
      compositorTestRunner.release();
    }
  }

  @Test
  public void compositeTwoInputs_withOneFrameFromEach_matchesExpectedBitmap() throws Exception {
    String testId = testName.getMethodName();
    compositorTestRunner = new VideoCompositorTestRunner(testId, useSharedExecutor);

    compositorTestRunner.queueBitmapsToBothInputs(/* count= */ 1);

    saveAndAssertBitmapMatchesExpected(
        testId,
        compositorTestRunner.inputBitmapReader1.getBitmap(),
        /* actualBitmapLabel= */ "actualCompositorInputBitmap1",
        GRAYSCALE_PNG_ASSET_PATH);
    saveAndAssertBitmapMatchesExpected(
        testId,
        compositorTestRunner.inputBitmapReader2.getBitmap(),
        /* actualBitmapLabel= */ "actualCompositorInputBitmap2",
        ROTATE180_PNG_ASSET_PATH);
    compositorTestRunner.saveAndAssertFirstCompositedBitmapMatchesExpected(
        GRAYSCALE_AND_ROTATE180_COMPOSITE_PNG_ASSET_PATH);
  }

  @Test
  public void compositeTwoInputs_withFiveFramesFromEach_matchesExpectedTimestamps()
      throws Exception {
    String testId = testName.getMethodName();
    compositorTestRunner = new VideoCompositorTestRunner(testId, useSharedExecutor);

    compositorTestRunner.queueBitmapsToBothInputs(/* count= */ 5);

    ImmutableList<Long> expectedTimestamps =
        ImmutableList.of(
            0 * C.MICROS_PER_SECOND,
            1 * C.MICROS_PER_SECOND,
            2 * C.MICROS_PER_SECOND,
            3 * C.MICROS_PER_SECOND,
            4 * C.MICROS_PER_SECOND);
    assertThat(compositorTestRunner.inputBitmapReader1.getOutputTimestamps())
        .containsExactlyElementsIn(expectedTimestamps)
        .inOrder();
    assertThat(compositorTestRunner.inputBitmapReader2.getOutputTimestamps())
        .containsExactlyElementsIn(expectedTimestamps)
        .inOrder();
    assertThat(compositorTestRunner.compositedTimestamps)
        .containsExactlyElementsIn(expectedTimestamps)
        .inOrder();
    compositorTestRunner.saveAndAssertFirstCompositedBitmapMatchesExpected(
        GRAYSCALE_AND_ROTATE180_COMPOSITE_PNG_ASSET_PATH);
  }

  @Test
  public void compositeTwoInputs_withTenFramesFromEach_matchesExpectedFrameCount()
      throws Exception {
    String testId = testName.getMethodName();
    compositorTestRunner = new VideoCompositorTestRunner(testId, useSharedExecutor);
    int numberOfFramesToQueue = 10;

    compositorTestRunner.queueBitmapsToBothInputs(numberOfFramesToQueue);

    assertThat(compositorTestRunner.inputBitmapReader1.getOutputTimestamps())
        .hasSize(numberOfFramesToQueue);
    assertThat(compositorTestRunner.inputBitmapReader2.getOutputTimestamps())
        .hasSize(numberOfFramesToQueue);
    assertThat(compositorTestRunner.compositedTimestamps).hasSize(numberOfFramesToQueue);
    compositorTestRunner.saveAndAssertFirstCompositedBitmapMatchesExpected(
        GRAYSCALE_AND_ROTATE180_COMPOSITE_PNG_ASSET_PATH);
  }

  /**
   * A test runner for {@link DefaultVideoCompositor} tests.
   *
   * <p>Composites input bitmaps from two input sources.
   */
  private static final class VideoCompositorTestRunner {
    private static final int COMPOSITOR_TIMEOUT_MS = 5_000;
    private static final Effect ROTATE_180_EFFECT =
        new ScaleAndRotateTransformation.Builder().setRotationDegrees(180).build();
    private static final Effect GRAYSCALE_EFFECT = RgbFilter.createGrayscaleFilter();

    public final TextureBitmapReader inputBitmapReader1;
    public final TextureBitmapReader inputBitmapReader2;
    public final List<Long> compositedTimestamps;
    private final VideoFrameProcessorTestRunner inputVideoFrameProcessorTestRunner1;
    private final VideoFrameProcessorTestRunner inputVideoFrameProcessorTestRunner2;
    private final VideoCompositor videoCompositor;
    private final @Nullable ExecutorService sharedExecutorService;
    private final AtomicReference<VideoFrameProcessingException> compositionException;
    private final AtomicReference<Bitmap> compositedFirstOutputBitmap;
    private final CountDownLatch compositorEnded;
    private final String testId;

    public VideoCompositorTestRunner(String testId, boolean useSharedExecutor)
        throws GlUtil.GlException, VideoFrameProcessingException {
      this.testId = testId;
      sharedExecutorService =
          useSharedExecutor ? Util.newSingleThreadExecutor("Effect:Shared:GlThread") : null;
      EGLContext sharedEglContext = AndroidTestUtil.createOpenGlObjects();
      GlObjectsProvider glObjectsProvider =
          new DefaultGlObjectsProvider(
              /* sharedEglContext= */ useSharedExecutor ? null : sharedEglContext);

      compositionException = new AtomicReference<>();
      compositedFirstOutputBitmap = new AtomicReference<>();
      compositedTimestamps = new CopyOnWriteArrayList<>();
      compositorEnded = new CountDownLatch(1);
      videoCompositor =
          new DefaultVideoCompositor(
              getApplicationContext(),
              glObjectsProvider,
              sharedExecutorService,
              new VideoCompositor.Listener() {
                @Override
                public void onError(VideoFrameProcessingException exception) {
                  compositionException.set(exception);
                  compositorEnded.countDown();
                }

                @Override
                public void onEnded() {
                  compositorEnded.countDown();
                }
              },
              /* textureOutputListener= */ (GlTextureInfo outputTexture,
                  long presentationTimeUs,
                  DefaultVideoFrameProcessor.ReleaseOutputTextureCallback
                      releaseOutputTextureCallback,
                  long syncObject) -> {
                if (!useSharedExecutor) {
                  GlUtil.awaitSyncObject(syncObject);
                }
                if (compositedFirstOutputBitmap.get() == null) {
                  compositedFirstOutputBitmap.set(
                      BitmapPixelTestUtil.createArgb8888BitmapFromFocusedGlFramebuffer(
                          outputTexture.width, outputTexture.height));
                }
                compositedTimestamps.add(presentationTimeUs);
                releaseOutputTextureCallback.release(presentationTimeUs);
              },
              /* textureOutputCapacity= */ 1);
      inputBitmapReader1 = new TextureBitmapReader();
      inputVideoFrameProcessorTestRunner1 =
          createVideoFrameProcessorTestRunnerBuilder(
                  testId,
                  inputBitmapReader1,
                  videoCompositor,
                  sharedExecutorService,
                  glObjectsProvider)
              .setEffects(GRAYSCALE_EFFECT)
              .build();
      inputBitmapReader2 = new TextureBitmapReader();
      inputVideoFrameProcessorTestRunner2 =
          createVideoFrameProcessorTestRunnerBuilder(
                  testId,
                  inputBitmapReader2,
                  videoCompositor,
                  sharedExecutorService,
                  glObjectsProvider)
              .setEffects(ROTATE_180_EFFECT)
              .build();
    }

    /**
     * Queues {@code count} bitmaps, with one bitmap per second, starting from and including 0
     * seconds.
     */
    public void queueBitmapsToBothInputs(int count) throws IOException, InterruptedException {
      inputVideoFrameProcessorTestRunner1.queueInputBitmap(
          readBitmap(ORIGINAL_PNG_ASSET_PATH),
          /* durationUs= */ count * C.MICROS_PER_SECOND,
          /* offsetToAddUs= */ 0,
          /* frameRate= */ 1);
      inputVideoFrameProcessorTestRunner2.queueInputBitmap(
          readBitmap(ORIGINAL_PNG_ASSET_PATH),
          /* durationUs= */ count * C.MICROS_PER_SECOND,
          /* offsetToAddUs= */ 0,
          /* frameRate= */ 1);
      inputVideoFrameProcessorTestRunner1.endFrameProcessing();
      inputVideoFrameProcessorTestRunner2.endFrameProcessing();

      videoCompositor.signalEndOfInputSource(/* inputId= */ 0);
      videoCompositor.signalEndOfInputSource(/* inputId= */ 1);
      @Nullable Exception endCompositingException = null;
      try {
        if (!compositorEnded.await(COMPOSITOR_TIMEOUT_MS, MILLISECONDS)) {
          endCompositingException = new IllegalStateException("Compositing timed out.");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        endCompositingException = e;
      }

      assertThat(compositionException.get()).isNull();
      assertThat(endCompositingException).isNull();
    }

    public void saveAndAssertFirstCompositedBitmapMatchesExpected(String expectedBitmapPath)
        throws IOException {
      saveAndAssertBitmapMatchesExpected(
          testId,
          compositedFirstOutputBitmap.get(),
          /* actualBitmapLabel= */ "compositedFirstOutputBitmap",
          expectedBitmapPath);
    }

    public void release() {
      inputVideoFrameProcessorTestRunner1.release();
      inputVideoFrameProcessorTestRunner2.release();
      videoCompositor.release();

      if (sharedExecutorService != null) {
        try {
          sharedExecutorService.shutdown();
          if (!sharedExecutorService.awaitTermination(COMPOSITOR_TIMEOUT_MS, MILLISECONDS)) {
            throw new IllegalStateException("Missed shutdown timeout.");
          }
        } catch (InterruptedException unexpected) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(unexpected);
        }
      }
    }

    private static VideoFrameProcessorTestRunner.Builder createVideoFrameProcessorTestRunnerBuilder(
        String testId,
        TextureBitmapReader textureBitmapReader,
        VideoCompositor videoCompositor,
        @Nullable ExecutorService executorService,
        GlObjectsProvider glObjectsProvider) {
      int inputId = videoCompositor.registerInputSource();
      DefaultVideoFrameProcessor.Factory.Builder defaultVideoFrameProcessorFactoryBuilder =
          new DefaultVideoFrameProcessor.Factory.Builder()
              .setGlObjectsProvider(glObjectsProvider)
              .setTextureOutput(
                  /* textureOutputListener= */ (GlTextureInfo outputTexture,
                      long presentationTimeUs,
                      DefaultVideoFrameProcessor.ReleaseOutputTextureCallback
                          releaseOutputTextureCallback,
                      long syncObject) -> {
                    GlUtil.awaitSyncObject(syncObject);
                    textureBitmapReader.readBitmap(outputTexture, presentationTimeUs);
                    videoCompositor.queueInputTexture(
                        inputId, outputTexture, presentationTimeUs, releaseOutputTextureCallback);
                  },
                  /* textureOutputCapacity= */ 1);
      if (executorService != null) {
        defaultVideoFrameProcessorFactoryBuilder.setExecutorService(executorService);
      }
      return new VideoFrameProcessorTestRunner.Builder()
          .setTestId(testId)
          .setVideoFrameProcessorFactory(defaultVideoFrameProcessorFactoryBuilder.build())
          .setInputColorInfo(ColorInfo.SRGB_BT709_FULL)
          .setBitmapReader(textureBitmapReader);
    }
  }

  private static void saveAndAssertBitmapMatchesExpected(
      String testId, Bitmap actualBitmap, String actualBitmapLabel, String expectedBitmapAssetPath)
      throws IOException {
    maybeSaveTestBitmap(testId, actualBitmapLabel, actualBitmap, /* path= */ null);
    float averagePixelAbsoluteDifference =
        BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
            readBitmap(expectedBitmapAssetPath), actualBitmap, testId);
    assertThat(averagePixelAbsoluteDifference)
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE_DIFFERENT_DEVICE);
  }
}