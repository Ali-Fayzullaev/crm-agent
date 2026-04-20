FROM golang:1.22-alpine AS builder
WORKDIR /app

# Cache dependencies
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /crm-backend ./cmd/server

# ── Final minimal image ───────────────────────────────────────
FROM alpine:3.19
RUN apk add --no-cache ca-certificates wget
WORKDIR /app
COPY --from=builder /crm-backend .

EXPOSE 8080
ENTRYPOINT ["./crm-backend"]
