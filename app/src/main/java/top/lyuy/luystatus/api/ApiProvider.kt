package top.lyuy.luystatus.api

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiProvider {

    private const val BASE_URL = "https://queue.lyuy.top"


    private lateinit var appContext: Context

   //初始化函数
    fun init(context: Context) {
        appContext = context.applicationContext
    }

   //读取api key
    private fun getApiKey(): String? {
        return appContext
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("api_key", null)
    }

   //添加Header
    private val authInterceptor = Interceptor { chain ->
        val apiKey = getApiKey()

        val builder = chain.request()
            .newBuilder()

        if (!apiKey.isNullOrBlank()) {
            builder.addHeader("X-API-Key", apiKey)
        }

        chain.proceed(builder.build())
    }
    //启动okhttp客户端
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val api: Api by lazy {
        retrofit.create(Api::class.java)
    }
}
