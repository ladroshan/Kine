package com.kine.internal

import com.kine.client.KineClient
import com.kine.connections.ConnectionChecker
import com.kine.converters.Converter
import com.kine.converters.RawResponseConverter
import com.kine.exceptions.*
import com.kine.extensions.firstResultOrNull
import com.kine.extensions.getClassInstance
import com.kine.extensions.getClassInstanceOrNull
import com.kine.interceptors.PreNetworkKineInterceptor
import com.kine.log.Logger.setLocalLevel
import com.kine.policies.DefaultRetryPolicy
import com.kine.policies.RetryPolicy
import com.kine.request.RequestFields
import com.kine.response.KineResponse
import com.kine.timer.TimerManager
import java.io.File
import java.io.InputStream
import java.util.*


/**
 * internal class that takes care of caching , parsing and check other params for a request
 */
internal object RequestManager : IRequestManager {
    private var kineClients: ArrayList<KineClient>? = createDefaultClients()
    private var converters: ArrayList<Converter>? = createDefaultConverters()
    private var interceptors: ArrayList<PreNetworkKineInterceptor>? = createDefaultInterceptors()
    private var retryPolicy: RetryPolicy? = DefaultRetryPolicy()
    private var networkPolicy = 0
    private var baseUrl: String? = null
    private var connectionChecker: ConnectionChecker? = null
    private var headers: HashMap<String, String?>? = null
    private var timerManager: TimerManager = TimerManager

    private const val jsonConverterClass = "com.kine.android.converters.JsonConverter"

    private const val gsonConverterClass = "com.kine.converters.GsonConverter"

    private const val moshiConverterClass = "com.kine.converters.MoshiConverter"

    private fun createDefaultConverters(): ArrayList<Converter> {
        return arrayListOf<Converter>().apply {
            jsonConverterClass.getClassInstance<Converter, RawResponseConverter>(
                RawResponseConverter()
            ).apply {
                add(this)
            }
            moshiConverterClass.getClassInstanceOrNull<Converter>()?.apply {
                add(this)
            }
            gsonConverterClass.getClassInstanceOrNull<Converter>()?.apply {
                add(this)
            }
        }
    }

    private const val bitmapInterceptorClass = "com.kine.interceptors.PreNetworkKineInterceptor"

    private fun createDefaultInterceptors(): ArrayList<PreNetworkKineInterceptor> {
        return arrayListOf<PreNetworkKineInterceptor>().apply {
            /*  bitmapInterceptorClass.getClassInstanceOrNull<PreNetworkKineInterceptor>()?.apply {
                  add(this)
              }*/
        }
    }

    private const val okHttpClientClass = "com.kine.client.OkHttpKineClient"

    private fun createDefaultClients(): ArrayList<KineClient>? {
        return arrayListOf<KineClient>().apply {
            okHttpClientClass.getClassInstanceOrNull<KineClient>()?.apply {
                add(this)
            }
        }
    }

    @Suppress("NAME_SHADOWING")
    override fun <F> executeRequest(requestFields: RequestFields, clazz: KineClass<F>): KineResponse<F>? {
        setLocalLevel(requestFields.logLevel)
        if (requestFields.kineCacheControl.networkPolicy == 0) {
            requestFields.kineCacheControl.networkPolicy = networkPolicy
        }
        if (!interceptors.isNullOrEmpty()) {
            val result = interceptors?.firstResultOrNull {
                it.intercept(requestFields, clazz.clazz)
            }
            if (result?.body != null) {
                return result
            }
        }
        if (requestFields.kineClient != null) {
            if (requestFields.kineClient.canHandleRequest(requestFields.dataFields.url, requestFields.dataFields.method)) {
                return enqueueRequest(requestFields.kineClient, requestFields, clazz)
            } else {
                throw MisMatchClientException(
                    "the provided kineClient cannot handle " +
                            "this type of requestFields, please set kineClient that can " +
                            "handle this type of requestFields" + " " + "by Changing the kineClient.canHandleRequest(url, method) method"
                )
            }
        }
        if (kineClients.isNullOrEmpty()) {
            throw NoClientFoundException()
        }
        kineClients?.firstOrNull {
            it.canHandleRequest(requestFields.dataFields.url, requestFields.dataFields.method).apply {
                return enqueueRequest(it, requestFields, clazz)
            }
        } ?: throw MisMatchClientException()
        return null
    }


    @Suppress("unused")
    fun cancelAllRequests(reqTAG: String?=null) {
        kineClients?.forEach {
            it.cancelAllRequests(reqTAG)
        }
    }

    @Suppress("NAME_SHADOWING")
    private fun <F> enqueueRequest(
        kineClient: KineClient,
        requestFields: RequestFields,
        clazz: KineClass<F>
    ): KineResponse<F>? {
        val url = requestFields.dataFields.url
        if (baseUrl != null) {
            if (!url.contains("://")) {
                requestFields.dataFields.url = baseUrl + url
            }
        } else if (url.isEmpty()) {
            throw UrlMalformedException("url is empty")
        } else if (!url.contains("://")) {
            throw UrlMalformedException("baseUrl is not set, either pass full url or set base " + "url")
        }
        val newHeader = requestFields.dataFields.headers ?: hashMapOf()
        headers?.let {
            newHeader.putAll(it)
        }
        requestFields.retryPolicy = requestFields.retryPolicy ?: retryPolicy
        return addRequest(kineClient, requestFields, clazz)
    }

    private fun <F> addRequest(
        kineClient: KineClient, requestFields: RequestFields, clazz: KineClass<F>
    ): KineResponse<F>? {
        val responseClazz = when {
            clazz.isAssignableFrom(ByteArray::class.java) -> {
                ByteArray::class.java
            }
            clazz.isAssignableFrom(File::class.java) -> {
                File::class.java
            }
            clazz.isAssignableFrom(InputStream::class.java) -> {
                InputStream::class.java
            }
            clazz.isAssignableFrom(String::class.java) -> {
                String::class.java
            }
            else -> {
                clazz.clazz
            }
        }
        val response = kineClient.execute(requestFields, responseClazz)
        return parseResponse(response, requestFields, clazz)
    }

    private fun <T, F> parseResponse(
        kineResponse: KineResponse<T>, requestFields: RequestFields, clazz: KineClass<F>
    ): KineResponse<F>? {
        if (kineResponse.body == null) {
            throw NullResponseException()
        }
        val timer = timerManager.start()
        val responseValue: F = parseDataToModel(kineResponse.body, requestFields, requestFields.converter, clazz)
        val parseResponse = KineResponse(
            responseValue, kineResponse.headers, kineResponse.statusCode,
            kineResponse.networkTimeMs, kineResponse.loadedFrom
        )
        if (parseResponse.body != null) {
            parseResponse.parseTime = timer.stop()
            return parseResponse
        } else {
            throw ParseException()
        }
    }

    @Suppress("NAME_SHADOWING")
    @Throws(Throwable::class)
    private fun <T> parseDataToModel(
        response: Any,
        requestFields: RequestFields,
        converter: Converter?,
        clazz: KineClass<T>
    ): T {
        if (converter != null) {
            return converter.convert(response, requestFields, clazz.clazz)
                ?: throw MisMatchConverterException()
        }
        if (converters.isNullOrEmpty()) {
            throw NoConverterFoundException()
        }
        return converters!!.firstResultOrNull<Converter, T> {
            it.convert(response, requestFields, clazz.clazz)
        } ?: throw MisMatchConverterException()
    }


    fun setConverters(converters: ArrayList<Converter>?) {
        if (converters.isNullOrEmpty()) {
            return
        }
        val converter = this.converters?.firstOrNull()
        //add the user specified converters with the default string converter
        this.converters = (converters)
        converter?.apply {
            this@RequestManager.converters!!.add(this)
        }
    }

    fun setClients(kineClients: ArrayList<KineClient>?) {
        if (kineClients.isNullOrEmpty()) {
            return
        }
        //only overwrite the clients if user has specified a client
        this.kineClients = (kineClients)
    }

    fun setConnectionChecker(connectionChecker: ConnectionChecker?) {
        this.connectionChecker = connectionChecker
    }

    // if there are no connectionChecker don't check for network connection and just return true
    fun isConnected(): Boolean {
        return connectionChecker == null || connectionChecker!!.isConnected()
    }

    fun setNetworkPolicy(networkPolicy: Int) {
        RequestManager.networkPolicy = networkPolicy
    }

    fun setRetryPolicy(retryPolicy: RetryPolicy?) {
        RequestManager.retryPolicy = retryPolicy
    }

    fun setHeaders(headers: HashMap<String, String?>?) {
        RequestManager.headers = headers
    }

    fun setBaseUrl(baseUrl: String?) {
        RequestManager.baseUrl = baseUrl
    }

}