package ru.childwatch.shared.attention

import android.content.Context
import org.json.JSONObject

class AttentionSignalCommandHandler(
    context: Context,
    private val ownDeviceId: () -> String,
    private val emitStatus: (JSONObject) -> Unit,
    private val notificationHost: NotificationHost
) : AttentionSignalController.Callback {

    interface NotificationHost {
        fun show(
            request: AttentionSignalRequest,
            onStop: () -> Unit
        )

        fun cancel(requestId: String)
    }

    private val controller =
        AttentionSignalController(
            context,
            this
        )

    fun handle(
        event: String,
        payload: JSONObject?
    ) {
        when (event) {
            AttentionSignalContract.EVENT_START -> {
                val request = payload
                    ?.let(
                        AttentionSignalRequest::fromJson
                    )
                    ?: return

                val mine = ownDeviceId().trim()

                if (request.targetDeviceId != mine) {
                    send(
                        request,
                        AttentionSignalStatus.REJECTED,
                        "WRONG_TARGET",
                        "TARGET_DEVICE_MISMATCH"
                    )
                    return
                }

                if (request.expiresAt <=
                    System.currentTimeMillis()
                ) {
                    send(
                        request,
                        AttentionSignalStatus.EXPIRED,
                        "TTL_EXPIRED"
                    )
                    return
                }

                send(
                    request,
                    AttentionSignalStatus.DELIVERED
                )
                controller.start(request)
            }

            AttentionSignalContract.EVENT_STOP -> {
                val requestId = payload
                    ?.optString("requestId")
                    .orEmpty()

                val stopped = controller.stop(
                    requestId.takeIf(
                        String::isNotBlank
                    ),
                    "REMOTE_REQUEST"
                )

                if (!stopped &&
                    requestId.isNotBlank()
                ) {
                    emitStatus(
                        AttentionSignalStatusEvent(
                            requestId = requestId,
                            targetDeviceId =
                                ownDeviceId(),
                            status =
                                AttentionSignalStatus
                                    .REJECTED,
                            reason = "NOT_ACTIVE",
                            errorCode =
                                "SIGNAL_NOT_ACTIVE"
                        ).toJson()
                    )
                }
            }
        }
    }

    fun stopLocally() {
        controller.stop(reason = "LOCAL_USER")
    }

    override fun onStarted(
        request: AttentionSignalRequest
    ) {
        notificationHost.show(request) {
            controller.stop(
                request.requestId,
                "LOCAL_USER"
            )
        }

        send(
            request,
            AttentionSignalStatus.STARTED
        )
    }

    override fun onStopped(
        request: AttentionSignalRequest,
        reason: String
    ) {
        notificationHost.cancel(request.requestId)
        send(
            request,
            AttentionSignalStatus.STOPPED,
            reason
        )
    }

    override fun onCompleted(
        request: AttentionSignalRequest
    ) {
        notificationHost.cancel(request.requestId)
        send(
            request,
            AttentionSignalStatus.COMPLETED,
            "TIMEOUT"
        )
    }

    override fun onFailed(
        request: AttentionSignalRequest,
        code: String,
        error: Throwable?
    ) {
        notificationHost.cancel(request.requestId)
        send(
            request,
            AttentionSignalStatus.FAILED,
            "PLAYBACK_FAILED",
            code,
            error?.message
        )
    }

    private fun send(
        request: AttentionSignalRequest,
        status: AttentionSignalStatus,
        reason: String? = null,
        errorCode: String? = null,
        message: String? = null
    ) {
        emitStatus(
            AttentionSignalStatusEvent(
                requestId = request.requestId,
                targetDeviceId =
                    request.targetDeviceId,
                status = status,
                reason = reason,
                errorCode = errorCode,
                message = message
            ).toJson()
        )
    }
}
