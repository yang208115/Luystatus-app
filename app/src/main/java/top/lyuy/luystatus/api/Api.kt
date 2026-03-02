package top.lyuy.luystatus.api


import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.POST



interface Api {

    /**
     * Push 任意 JSON 到队列
     */
    @POST("queue/push")
    suspend fun push(
        @Body body: JsonObject
    ): PushResponse

    /**
     * Peek 队列头部（不删除）
     * 队列为空时返回 404
     */
    @POST("queue/peek")
    suspend fun peek(): PeekResponse

    /**
     * Pop 队列头部（删除）
     * 队列为空时返回 404
     */
    @POST("queue/pop")
    suspend fun pop(): PopResponse

    /**
     * 获取队列长度
     */
    @POST("queue/size")
    suspend fun size(): SizeResponse

    /**
     * 列出所有队列项
     */
    @POST("queue/list")
    suspend fun list(): ListResponse
}
