from __future__ import annotations


class ServiceError(Exception):
    def __init__(self, code: str, message: str, *, retryable: bool = False, status: int = 422,
                 retry_after: float | None = None, request_id: str | None = None):
        super().__init__(message)
        self.code, self.message = code, message
        self.retryable, self.status = retryable, status
        self.retry_after, self.request_id = retry_after, request_id

    def as_dict(self, stage: str = "VALIDATING") -> dict:
        return {"code": self.code, "message": self.message, "retryable": self.retryable,
                "retryAfterSeconds": self.retry_after, "providerRequestId": self.request_id, "stage": stage}


class LeaseLost(Exception):
    pass
