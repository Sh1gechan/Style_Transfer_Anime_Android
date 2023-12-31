/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.styletransfer

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.collections.set
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate



class StyleTransferModelExecutor(
  context: Context,
  private var useGPU: Boolean = false,
  var selectedModel: String = "hayao_style"

) {
  private var gpuDelegate: GpuDelegate? = null
  private var numberThreads = 4

  private val interpreterTransform: Interpreter

  private var fullExecutionTime = 0L
  private var preProcessTime = 0L
  private var stylePredictTime = 0L
  private var styleTransferTime = 0L
  private var postProcessTime = 0L

  init {
    val modelFileName = when (selectedModel) {
      "hayao_style" -> HAYAO_MODEL
      "paprika_style" -> PAPRIKA_MODEL
      "selfie2anime" -> SELFIE_MODEL
      "anime_sketch" -> ANIME_SKETCH_MODEL
      "open_sketch" -> OPENSKETCH_MODEL
      "contour_style" -> CONTOUR_MODEL
      else -> throw IllegalArgumentException("Invalid style model")
    }

    if (useGPU) {
      interpreterTransform = getInterpreter(context, modelFileName, true)
    } else {
      interpreterTransform = getInterpreter(context, modelFileName, false)
    }
  }

  companion object {
    private const val TAG = "StyleTransferMExec"
    private const val CONTENT_IMAGE_SIZE = 256
    private const val HAYAO_MODEL = "animeganv2_hayao_256x256_float16_quant.tflite"
    private const val PAPRIKA_MODEL = "animeganv2_paprika_256x256_float16_quant.tflite"
    private const val SELFIE_MODEL = "selfie2anime_256x256_float16_quant.tflite"
    private const val ANIME_SKETCH_MODEL = "anime_style_256x256_float16.tflite"
    private const val OPENSKETCH_MODEL = "opensketch_style_256x256_float16.tflite"
    private const val CONTOUR_MODEL = "contour_style_256x256_float16.tflite"
  }

  fun execute(
    contentImagePath: String,
    context: Context
  ): ModelExecutionResult {
    try {
      Log.i(TAG, "running models")

      fullExecutionTime = SystemClock.uptimeMillis()
      preProcessTime = SystemClock.uptimeMillis()

      val contentImage = ImageUtils.decodeBitmap(File(contentImagePath))
      val contentArray =
        ImageUtils.bitmapToByteBuffer(contentImage, CONTENT_IMAGE_SIZE, CONTENT_IMAGE_SIZE)
      val inputsForStyleTransfer = arrayOf(contentArray)

      // Executeメソッド内のoutputsForStyleTransferの初期化部分
      val outputsForStyleTransfer = HashMap<Int, Any>()
      val outputImage =
        if (selectedModel == "anime_sketch" || selectedModel == "open_sketch" || selectedModel == "contour_style") {
          Array(1) { Array(CONTENT_IMAGE_SIZE) { Array(CONTENT_IMAGE_SIZE) { FloatArray(1) } } }
        } else {
          Array(1) { Array(CONTENT_IMAGE_SIZE) { Array(CONTENT_IMAGE_SIZE) { FloatArray(3) } } }
        }
      outputsForStyleTransfer[0] = outputImage


      Log.i(TAG, "init image " + inputsForStyleTransfer)
      styleTransferTime = SystemClock.uptimeMillis()

      // モデルの実行前のログ
      Log.d(TAG, "Starting model execution for $selectedModel")

      interpreterTransform.runForMultipleInputsOutputs(
        inputsForStyleTransfer,
        outputsForStyleTransfer
      )
      styleTransferTime = SystemClock.uptimeMillis() - styleTransferTime
      Log.d(TAG, "Style apply Time to run: $styleTransferTime")

      // interpreterTransform.getOutputTensor(0) の戻り値が正しく取得できているかを確認
      val outputTensor = interpreterTransform.getOutputTensor(0)
      Log.d(TAG, "Output Tensor: $outputTensor")

      // モデルの実行後のログ
      Log.d(TAG, "Model execution completed for $selectedModel")
      val outputShape = interpreterTransform.getOutputTensor(0).shape().contentToString()
      Log.d(TAG, "Model Output Shape: $outputShape")

      // スタイル適用処理前のログ
      Log.d(TAG, "Applying style for $selectedModel")

      postProcessTime = SystemClock.uptimeMillis()
//      var styledImage =
//        ImageUtils.convertArrayToBitmap(outputImage, CONTENT_IMAGE_SIZE, CONTENT_IMAGE_SIZE)

      // Executeメソッド内のstyledImageの生成部分
      var styledImage: Bitmap
      if (selectedModel == "anime_sketch" || selectedModel == "open_sketch" || selectedModel == "contour_style") {
        Log.d(TAG, "Converting to grayscale bitmap")
        styledImage = ImageUtils.convertArrayToGrayscaleBitmap(outputImage, CONTENT_IMAGE_SIZE, CONTENT_IMAGE_SIZE)
      } else {
        Log.d(TAG, "Converting to color bitmap")
        styledImage = ImageUtils.convertArrayToBitmap(outputImage, CONTENT_IMAGE_SIZE, CONTENT_IMAGE_SIZE)
      }


      postProcessTime = SystemClock.uptimeMillis() - postProcessTime

      fullExecutionTime = SystemClock.uptimeMillis() - fullExecutionTime
      Log.d(TAG, "Time to run everything: $fullExecutionTime")

      return ModelExecutionResult(
        styledImage,
        preProcessTime,
        stylePredictTime,
        styleTransferTime,
        postProcessTime,
        fullExecutionTime,
        formatExecutionLog()
      )
    } catch (e: Exception) {
      val exceptionLog = "something went wrong: ${e.message}"
      Log.d(TAG, exceptionLog)

      val emptyBitmap =
        ImageUtils.createEmptyBitmap(
          CONTENT_IMAGE_SIZE,
          CONTENT_IMAGE_SIZE
        )
      return ModelExecutionResult(
        emptyBitmap, errorMessage = e.message!!
      )
    }
  }



  @Throws(IOException::class)
  private fun loadModelFile(context: Context, modelFile: String): MappedByteBuffer {
    try {
      Log.d(TAG, "Loading model file: $modelFile")
    val fileDescriptor = context.assets.openFd(modelFile)
    val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
    val fileChannel = inputStream.channel
    val startOffset = fileDescriptor.startOffset
    val declaredLength = fileDescriptor.declaredLength
    val retFile = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    fileDescriptor.close()
    return retFile
    } catch (e: IOException) {
      Log.e(TAG, "Failed to load model file: $modelFile", e)
      throw e
    }
  }

  @Throws(IOException::class)
  private fun getInterpreter(
    context: Context,
    modelName: String,
    useGpu: Boolean = false
  ): Interpreter {
    try {
      Log.d(TAG, "Getting interpreter for model: $modelName, useGPU: $useGpu")

      val tfliteOptions = Interpreter.Options()
    tfliteOptions.setNumThreads(numberThreads)

    gpuDelegate = null
    if (useGpu) {
      gpuDelegate = GpuDelegate()
      tfliteOptions.addDelegate(gpuDelegate)
    }

    tfliteOptions.setNumThreads(numberThreads)
    return Interpreter(loadModelFile(context, modelName), tfliteOptions)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to get interpreter for model: $modelName", e)
      throw e
    }
    }

  private fun formatExecutionLog(): String {
    val sb = StringBuilder()
    sb.append("Input Image Size: $CONTENT_IMAGE_SIZE x $CONTENT_IMAGE_SIZE\n")
    sb.append("GPU enabled: $useGPU\n")
    sb.append("Number of threads: $numberThreads\n")
    sb.append("Transferring style execution time: $styleTransferTime ms\n")
    sb.append("Post-process execution time: $postProcessTime ms\n")
    sb.append("Full execution time: $fullExecutionTime ms\n")
    return sb.toString()
  }

  fun close() {
    interpreterTransform.close()
    if (gpuDelegate != null) {
      gpuDelegate!!.close()
    }
  }
}
