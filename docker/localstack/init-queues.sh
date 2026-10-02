#!/usr/bin/env bash
# LocalStack init hook (runs once when LocalStack is READY).
# Creates the orders queue and its dead-letter queue (DLQ) with a redrive policy.
set -euo pipefail

ORDERS_QUEUE="${ORDERS_QUEUE_NAME:-orders}"
DLQ="${ORDERS_DLQ_NAME:-orders-dlq}"
MAX_RECEIVE_COUNT="${ORDERS_MAX_RECEIVE_COUNT:-3}"

echo "[init] Creating DLQ ${DLQ}"
awslocal sqs create-queue --queue-name "${DLQ}" >/dev/null

DLQ_URL="$(awslocal sqs get-queue-url --queue-name "${DLQ}" --query QueueUrl --output text)"
DLQ_ARN="$(awslocal sqs get-queue-attributes --queue-url "${DLQ_URL}" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)"

# RedrivePolicy must be a JSON string nested inside the attributes JSON.
REDRIVE="{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"${MAX_RECEIVE_COUNT}\\\"}"

echo "[init] Creating queue ${ORDERS_QUEUE} (redrive to ${DLQ} after ${MAX_RECEIVE_COUNT} receives)"
awslocal sqs create-queue --queue-name "${ORDERS_QUEUE}" \
  --attributes "{\"RedrivePolicy\":\"${REDRIVE}\",\"VisibilityTimeout\":\"30\"}" >/dev/null

echo "[init] Queues ready:"
awslocal sqs list-queues
