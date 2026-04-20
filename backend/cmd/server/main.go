package main

import (
	"context"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/crm/backend/internal/api"
	"github.com/crm/backend/internal/queue"
	"github.com/crm/backend/internal/repository"
	"github.com/crm/backend/internal/service"
	"github.com/crm/backend/internal/ws"
	"go.uber.org/zap"
)

func main() {
	// ── Logger ───────────────────────────────────────────────────
	log, err := zap.NewProduction()
	if err != nil {
		panic(err)
	}
	defer log.Sync() //nolint:errcheck

	// ── Context with graceful shutdown ───────────────────────────
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	// ── Database ─────────────────────────────────────────────────
	dbURL := mustEnv("DATABASE_URL")
	repo, err := repository.New(ctx, dbURL)
	if err != nil {
		log.Fatal("connect to database", zap.Error(err))
	}
	defer repo.Close()
	log.Info("database connected")

	// ── Redis ────────────────────────────────────────────────────
	redisURL := mustEnv("REDIS_URL")
	redisQ, err := queue.New(ctx, redisURL)
	if err != nil {
		log.Fatal("connect to redis", zap.Error(err))
	}
	defer redisQ.Close() //nolint:errcheck
	log.Info("redis connected")

	// ── WebSocket Hub ─────────────────────────────────────────────
	hub := ws.NewHub(log)
	go hub.Run()

	// ── Service ───────────────────────────────────────────────────
	svc := service.New(repo, hub, redisQ, log)

	// ── HTTP Router ───────────────────────────────────────────────
	router := api.NewRouter(svc, hub, repo, log)

	addr := envOr("SERVER_ADDR", ":8080")
	srv := &http.Server{
		Addr:         addr,
		Handler:      router,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 60 * time.Second, // long enough for WS upgrade
		IdleTimeout:  120 * time.Second,
	}

	// ── Start server ─────────────────────────────────────────────
	go func() {
		log.Info("server starting", zap.String("addr", addr))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal("server error", zap.Error(err))
		}
	}()

	// ── Wait for shutdown signal ──────────────────────────────────
	<-ctx.Done()
	log.Info("shutdown signal received")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Error("graceful shutdown failed", zap.Error(err))
	}
	log.Info("server stopped")
}

func mustEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		panic("required env var not set: " + key)
	}
	return v
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
