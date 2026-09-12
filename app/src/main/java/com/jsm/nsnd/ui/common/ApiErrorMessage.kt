package com.jsm.nsnd.ui.common

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Response

/** 서버 응답과 네트워크 예외를 사용자가 이해할 수 있는 토스트 문구로 변환합니다. */
object ApiErrorMessage {
    fun fromResponse(response: Response<*>): String {
        val detail = runCatching {
            val body = response.errorBody()?.string().orEmpty()
            if (body.isBlank()) null else JSONObject(body).optString("detail").takeIf { it.isNotBlank() }
        }.getOrNull()
        return detail ?: when (response.code()) {
            400 -> "요청 내용을 확인해주세요."
            401 -> "로그인이 만료되었거나 다른 기기에서 로그인했습니다."
            403 -> "이 작업을 수행할 권한이 없습니다."
            404 -> "요청한 정보를 찾을 수 없습니다."
            409 -> "현재 상태와 충돌하여 작업을 완료할 수 없습니다."
            in 500..599 -> "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
            else -> "요청에 실패했습니다. (HTTP ${response.code()})"
        }
    }

    fun fromHttpException(error: HttpException): String =
        error.response()?.let(::fromResponse) ?: "요청에 실패했습니다. (HTTP ${error.code()})"

    fun fromThrowable(error: Throwable, action: String): String = when (error) {
        is SocketTimeoutException -> "$action 중 서버 응답 시간이 초과되었습니다."
        is ConnectException, is UnknownHostException ->
            "$action 중 서버에 연결하지 못했습니다. 서버 실행 상태와 주소를 확인하세요."
        is IOException -> "$action 중 네트워크 연결이 끊어졌습니다. 연결 상태를 확인하세요."
        else -> error.message?.takeIf { it.isNotBlank() }
            ?.let { "$action 중 오류가 발생했습니다: $it" }
            ?: "$action 중 알 수 없는 오류가 발생했습니다."
    }
}
