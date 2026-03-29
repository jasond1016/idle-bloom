package com.idlebloom.app.slideshow

import android.content.Context
import coil.ImageLoader
import com.idlebloom.app.data.SourceConfig
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient

object AuthenticatedImageLoaderFactory {

    fun create(context: Context, config: SourceConfig): ImageLoader {
        val authHeader = Credentials.basic(config.username, config.password)
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", authHeader)
                    .build()
                chain.proceed(request)
            })
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(okHttpClient)
            .crossfade(true)
            .build()
    }
}
