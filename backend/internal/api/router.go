package api

import (
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/crm/backend/internal/repository"
	"github.com/crm/backend/internal/service"
	"github.com/crm/backend/internal/ws"
	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/golang-jwt/jwt/v5"
	"go.uber.org/zap"
)

// NewRouter wires all routes and middleware and returns the http.Handler.
func NewRouter(
	svc *service.MessageService,
	hub *ws.Hub,
	repo *repository.Repository,
	log *zap.Logger,
) http.Handler {
	r := chi.NewRouter()

	jwtSecret := []byte(os.Getenv("JWT_SECRET"))

	// ── Global middleware ────────────────────────────────────────
	r.Use(middleware.RequestID)
	r.Use(middleware.RealIP)
	r.Use(zapLogger(log))
	r.Use(middleware.Recoverer)
	r.Use(corsMiddleware(os.Getenv("CORS_ORIGINS")))
	// NOTE: middleware.Timeout is NOT applied globally because it cancels the
	// request context, which breaks long-lived WebSocket connections (/ws/agent,
	// /ws/crm). Instead, apply it only to REST routes below.

	h := &handlers{svc: svc, hub: hub, repo: repo, log: log, jwtSecret: jwtSecret}

	httpTimeout := middleware.Timeout(30 * time.Second)

	// ── Health ───────────────────────────────────────────────────
	r.Get("/health", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	})

	// ── Auth ─────────────────────────────────────────────────────
	r.With(httpTimeout).Post("/api/v1/auth/login", h.operatorLogin)

	// ── Device registration (public — uses long-lived device token) ──
	r.With(httpTimeout).Post("/api/v1/devices/register", h.registerDevice)

	// ── Agent WebSocket (device JWT) ─────────────────────────────
	r.With(deviceAuthMiddleware(jwtSecret)).
		Get("/ws/agent", h.agentWebSocket)

	// ── Protected CRM routes (operator JWT) ──────────────────────
	r.Group(func(r chi.Router) {
		r.Use(operatorAuthMiddleware(jwtSecret))

		// Operator WebSocket (no HTTP timeout — long-lived connection)
		r.Get("/ws/crm", h.operatorWebSocket)

		// Devices
		r.With(httpTimeout).Get("/api/v1/devices", h.listDevices)

		// Conversations
		r.With(httpTimeout).Get("/api/v1/conversations", h.listConversations)
		r.With(httpTimeout).Get("/api/v1/conversations/{id}", h.getConversation)
		r.With(httpTimeout).Patch("/api/v1/conversations/{id}", h.updateConversation)
		r.With(httpTimeout).Get("/api/v1/conversations/{id}/messages", h.listMessages)
		r.With(httpTimeout).Post("/api/v1/conversations/{id}/reply", h.sendReply)
	})

	return r
}

// ─── JWT helpers ──────────────────────────────────────────────

const (
	claimSubject  = "sub"
	claimRole     = "role"
	claimDeviceID = "device_id"
)

func parseJWT(tokenStr string, secret []byte) (jwt.MapClaims, error) {
	token, err := jwt.Parse(tokenStr, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, jwt.ErrSignatureInvalid
		}
		return secret, nil
	}, jwt.WithValidMethods([]string{"HS256"}))
	if err != nil || !token.Valid {
		return nil, jwt.ErrSignatureInvalid
	}
	claims, ok := token.Claims.(jwt.MapClaims)
	if !ok {
		return nil, jwt.ErrTokenInvalidClaims
	}
	return claims, nil
}

func bearerToken(r *http.Request) string {
	auth := r.Header.Get("Authorization")
	if strings.HasPrefix(auth, "Bearer ") {
		return strings.TrimPrefix(auth, "Bearer ")
	}
	// Allow token in query param for WebSocket upgrades.
	return r.URL.Query().Get("token")
}

// ─── Middleware ───────────────────────────────────────────────

func deviceAuthMiddleware(secret []byte) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			claims, err := parseJWT(bearerToken(r), secret)
			if err != nil {
				http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
				return
			}
			if claims[claimRole] != "device" {
				http.Error(w, `{"error":"forbidden"}`, http.StatusForbidden)
				return
			}
			ctx := contextWithDeviceID(r.Context(), fmt.Sprint(claims[claimDeviceID]))
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func operatorAuthMiddleware(secret []byte) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			claims, err := parseJWT(bearerToken(r), secret)
			if err != nil {
				http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
				return
			}
			role, _ := claims[claimRole].(string)
			if role != "agent" && role != "admin" {
				http.Error(w, `{"error":"forbidden"}`, http.StatusForbidden)
				return
			}
			ctx := contextWithOperator(r.Context(), fmt.Sprint(claims[claimSubject]), role)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func corsMiddleware(origins string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Access-Control-Allow-Origin", origins)
			w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusNoContent)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

func zapLogger(log *zap.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)
			start := time.Now()
			next.ServeHTTP(ww, r)
			log.Info("http",
				zap.String("method", r.Method),
				zap.String("path", r.URL.Path),
				zap.Int("status", ww.Status()),
				zap.Duration("latency", time.Since(start)),
				zap.String("request_id", middleware.GetReqID(r.Context())),
			)
		})
	}
}
