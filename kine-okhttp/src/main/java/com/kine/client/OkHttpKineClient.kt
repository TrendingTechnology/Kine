package com.kine.client

import com.kine.KineRequest
import com.kine.client.extensions.download
import com.kine.exceptions.HttpStatusCodeException
import com.kine.exceptions.NullResponseBodyException
import com.kine.extensions.getMimeType
import com.kine.log.Logger
import com.kine.log.Logger.e
import com.kine.policies.DefaultRetryPolicy
import com.kine.policies.RetryPolicy
import com.kine.request.*
import com.kine.request.Request
import com.kine.request.RequestBody
import com.kine.response.KineResponse
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import okio.ByteString
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.util.concurrent.TimeUnit

@Suppress("unused")
open class OkHttpKineClient : KineClient {
    private var client: OkHttpClient? = null
    private var retryPolicy: RetryPolicy = DefaultRetryPolicy()

    @Suppress("unused")
    constructor(retryPolicy: RetryPolicy) : super() {
        createClient(retryPolicy)
    }

    @Suppress("unused")
    constructor() : super() {
        createClient()
    }

    @Suppress("unused")
    constructor(client: OkHttpClient?) : super() {
        this.client = client
    }

    private fun createClient(retryPolicy: RetryPolicy = this.retryPolicy) {
        client = OkHttpClient.Builder()
            .connectTimeout(
                retryPolicy.getCurrentTimeout().toLong(),
                TimeUnit.MILLISECONDS
            )
            .readTimeout(
                retryPolicy.getCurrentTimeout().toLong(),
                TimeUnit.MILLISECONDS
            )
            .addInterceptor(object : Interceptor {
                @Throws(IOException::class)
                override fun intercept(chain: Interceptor.Chain): Response {
                    val request = chain.request()
                    // try the request
                    var response = chain.proceed(request)
                    var tryCount = 0
                    while (!response.isSuccessful && tryCount < retryPolicy.getRetryCount()) {
                        e("intercept", "Request is not successful - $tryCount")
                        tryCount++
                        // Request customization: add request headers

                        val requestBuilder = request.newBuilder()
                            .headers(request.headers)
                            .tag(request.tag())
                            .cacheControl(request.cacheControl)
                            .url(request.url)
                            .method(request.method, request.body)

                        val newRequest = requestBuilder.build()
                        // retry the request
                        response.body?.close()
                        response = chain.proceed(newRequest)
                    }
                    // otherwise just pass the original response on
                    return response
                }
            }).build()
    }

    override fun canHandleRequest(url: String, method: Int): Boolean {
        return method <= KineRequest.Method.PATCH
    }

    override fun <T> execute(request: Request, clazz: Class<T>): KineResponse<T> {
        Logger.d(TAG, "Tag:${request.data.reqTAG}")
        Logger.d(TAG, "${request.data.reqTAG} request Url: ${request.data.url}")
        Logger.d(TAG, "${request.data.reqTAG} request Header: ${request.data.headers}")
        val builder = okhttp3.Request.Builder()
            .url(request.data.url)
            .tag(request.data.reqTAG)
        request.data.headers?.apply {
            for ((key1, value) in this) {
                builder.addHeader(key1, value ?: "")
            }
        }
        val requestBody = if (request is UploadRequest) {
            FileProgressRequestBody(getRequestBody(request.data.body), request.progressListener)
        } else {
            getRequestBody(request.data.body)
        }
        Logger.d(TAG, "${request.data.reqTAG} request Json Params: ${requestBody.contentType()}")
        when (request.data.method) {
            KineRequest.Method.GET -> builder.get()
            KineRequest.Method.POST -> builder.post(requestBody)
            KineRequest.Method.DELETE -> builder.delete(requestBody)
            KineRequest.Method.HEAD -> builder.head()
            KineRequest.Method.PATCH -> builder.patch(requestBody)
            KineRequest.Method.PUT -> builder.put(requestBody)
            else -> {
                throw IllegalArgumentException(
                    "okHttpClient does not support this request type, Use one of GET,POST,DELETE,HEAD,PATCH OR PUT"
                )
            }
        }
        val cacheControl = when (request.kineCacheControl.networkPolicy) {
            com.kine.cache.KineCacheControl.FORCE_CACHE -> {
                CacheControl.FORCE_CACHE
            }
            com.kine.cache.KineCacheControl.FORCE_NETWORK -> {
                CacheControl.FORCE_NETWORK
            }
            com.kine.cache.KineCacheControl.NO_CACHE -> {
                CacheControl.Builder().noCache().build()
            }
            com.kine.cache.KineCacheControl.NO_STORE -> {
                CacheControl.Builder().noStore().build()
            }
            com.kine.cache.KineCacheControl.CACHE_FOR_TIME -> {
                CacheControl.Builder()
                    .maxAge(request.kineCacheControl.cacheMaxAge, request.kineCacheControl.timeUnit)
                    .build()
            }
            else -> null
        }
        cacheControl?.apply {
            builder.cacheControl(cacheControl = this)
        }
        request.retryPolicy?.let {
            if (!it.isSame(retryPolicy)) {
                createClient(it)
            }
        }
        val response = client!!.newCall(builder.build()).execute()
        if (!response.isSuccessful) {
            Logger.e(TAG, request.data.reqTAG + " onErrorResponse >> errorCode: " + response.code)
            throw HttpStatusCodeException(code = response.code)
        }
        val body: ResponseBody? = response.body
        if (body == null) {
            Logger.e(TAG, "onResponse jsonObject: null")
            throw NullResponseBodyException()
        }
        val responseHeaders = response.headers
        val headers: HashMap<String, String>
        headers = HashMap(responseHeaders.size)
        var i = 0
        val size = responseHeaders.size
        while (i < size) {
            headers[responseHeaders.name(i)] = responseHeaders.value(i)
            i++
        }
        val responseValue: Any? = when {
            clazz.isAssignableFrom(File::class.java) && request is DownloadRequest -> {
                request.file.download(body, request.progressListener)
            }
            clazz.isAssignableFrom(String::class.java) -> {
                body.string().apply {
                    Logger.d(TAG, "onResponse String: $this")
                }
            }
            clazz.isAssignableFrom(ByteArray::class.java) -> {
                body.bytes()
            }
            clazz.isAssignableFrom(ByteString::class.java) -> {
                body.byteString()
            }
            clazz.isAssignableFrom(InputStream::class.java) -> {
                body.byteStream()
            }
            clazz.isAssignableFrom(Reader::class.java) -> {
                body.charStream()
            }
            clazz.isAssignableFrom(BufferedSource::class.java) -> {
                body.source()
            }
            else -> {
                body.string().apply {
                    Logger.d(TAG, "onResponse String: $this")
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return KineResponse(
            responseValue, headers, response.code,
            response.receivedResponseAtMillis - response.sentRequestAtMillis,
            KineResponse.LoadedFrom.NETWORK
        ) as KineResponse<T>
    }


    override fun cancelAllRequests(tag: String?) {
        if (tag == null) {
            return client?.dispatcher?.cancelAll() ?: Unit
        }
        val calls = client?.dispatcher?.runningCalls() ?: return
        for (call in calls) {
            if (call.request().tag() == tag && !call.isCanceled()) {
                call.cancel()
            }
        }
    }

    private fun getRequestBody(requestBody: RequestBody): okhttp3.RequestBody {
        return when (requestBody) {
            is StringRequestBody -> {
                (requestBody.body ?: "").toRequestBody(requestBody.mediaType.toMediaType())
            }
            is EncodedRequestBody -> {
                val builder = FormBody.Builder()
                requestBody.bodyParams.apply {
                    for ((key, value) in this.entries) {
                        builder.add(key, value)
                    }
                }
                requestBody.encodedBodyParams.apply {
                    for ((key, value) in this.entries) {
                        builder.addEncoded(key, value)
                    }
                }
                builder.build()
            }
            is MultiPartRequestBody -> {
                val builder = MultipartBody.Builder()
                    .setType(if (requestBody.mediaType == null) MULTIPART_FORM else requestBody.mediaType.toMediaType())
                //passing both meta data and file content for uploading
                requestBody.multiPartParams.apply {
                    for ((key, stringBody) in this.entries) {
                        val mediaType: MediaType? = stringBody.contentType?.toMediaTypeOrNull()
                        builder.addFormDataPart(
                            key,
                            null,
                            stringBody.value.toRequestBody(mediaType)
                        )
                    }
                }
                for ((key, fileBodies) in requestBody.multiPartFileParams.entries) {
                    for (fileBody in fileBodies) {
                        val fileName: String = fileBody.value.name
                        val mediaType: MediaType? = fileBody.contentType?.toMediaTypeOrNull()
                            ?: fileName.getMimeType().toMediaTypeOrNull()
                        builder.addFormDataPart(
                            key,
                            fileName,
                            fileBody.value.asRequestBody(mediaType)
                        )
                    }
                }
                builder.build()
            }
            else -> {
                "".toRequestBody(requestBody.mediaType.toMediaType())
            }
        }
    }

    @Suppress("unused")
    companion object {
        val JSON: MediaType = ContentType.JSON.toString().toMediaType()
        val STRING: MediaType = ContentType.STRING.toString().toMediaType()
        val MULTIPART_FORM: MediaType = ContentType.MULTIPART_FORM.toString().toMediaType()
        val TAG = OkHttpKineClient::class.java.simpleName
    }
}