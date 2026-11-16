package com.adobe.marketing.mobile.telemetry

/** Injectable time source — Omega lesson: deterministic tests need a clock double. */
fun interface Clock { fun nowMs(): Long }

/** Injectable ID source — recordId/operationId/batchId generation must accept test doubles. */
fun interface IdGenerator { fun newId(): String }
