#!/usr/bin/env bash
# =============================================================================
# run.sh — Spring PetClinic Application Launcher with Datadog APM
# =============================================================================
#
# Launches the Spring PetClinic application with the Datadog Java APM agent
# attached, enabling distributed tracing, continuous profiling, log
# correlation, and health metrics reporting.
#
# Prerequisites:
#   - Java 17+ installed and on PATH
#   - Datadog Java agent JAR downloaded to DD_AGENT_JAR path below
#     (download: https://dtdg.co/latest-java-tracer)
#   - A Datadog Agent running locally (default localhost:8126 for traces,
#     localhost:8125 for DogStatsD health metrics)
#
# Usage:
#   ./run.sh
#
# Override the log directory at runtime:
#   LOG_DIR=/tmp/logs ./run.sh
#
# =============================================================================
#
# DATADOG APM CONFIGURATION REFERENCE
# =============================================================================
# Full documentation:
#   Tracing  — https://docs.datadoghq.com/tracing/trace_collection/library_config/java/
#   Profiler — https://docs.datadoghq.com/profiler/enabling/java/
#
# All properties below have equivalent environment variables. System properties
# (-D flags) take precedence over environment variables. To convert between
# them: uppercase the name and replace '.' with '_'.
#   e.g.  dd.service  ->  DD_SERVICE
#
# -----------------------------------------------------------------------------
# -javaagent:<path>/dd-java-agent.jar
# -----------------------------------------------------------------------------
#   Attaches the Datadog dd-java-agent to the JVM. This must appear *before*
#   the -jar flag. The agent auto-instruments supported frameworks (Spring MVC,
#   JDBC, Servlet, etc.) and sends trace spans to the local Datadog Agent.
#   IMPORTANT: Never add dd-java-agent to the application classpath; always
#   load it exclusively via -javaagent.
#
# -----------------------------------------------------------------------------
# dd.profiling.enabled  (default: false)   Env: DD_PROFILING_ENABLED
# -----------------------------------------------------------------------------
#   Master switch for the Datadog Continuous Profiler. When true the agent
#   periodically collects profiling data (CPU, wall-clock, allocations, heap)
#   and uploads it to the Datadog profiling backend. This powers the
#   Continuous Profiler UI (flame graphs, hotspot analysis).
#
# -----------------------------------------------------------------------------
# dd.profiling.ddprof.enabled  (default: true since v1.7.0)
#   Env: DD_PROFILING_DDPROF_ENABLED
# -----------------------------------------------------------------------------
#   Enables the native Datadog Profiler engine (ddprof) instead of JFR (Java
#   Flight Recorder). The native engine is more accurate for CPU profiling
#   because it uses Linux perf events and has lower overhead. It also
#   integrates more tightly with APM traces for Endpoint Profiling.
#   Requires: JDK 8u352+, 11.0.17+, 17.0.5+, or 21+.
#   Note: Not effective on GraalVM native-image (JFR is used instead).
#
# -----------------------------------------------------------------------------
# dd.profiling.ddprof.cpu.enabled  (default: true when ddprof is enabled)
#   Env: DD_PROFILING_DDPROF_CPU_ENABLED
# -----------------------------------------------------------------------------
#   Activates CPU profiling within the native Datadog Profiler. Samples are
#   collected via Linux perf events, providing more accurate CPU usage data
#   than JFR-based CPU profiling. Useful for identifying hot methods and
#   CPU-bound bottlenecks.
#
# -----------------------------------------------------------------------------
# dd.profiling.ddprof.wall.enabled  (default: true since v1.7.0)
#   Env: DD_PROFILING_DDPROF_WALL_ENABLED
# -----------------------------------------------------------------------------
#   Activates wall-clock (elapsed time) profiling. Unlike CPU profiling which
#   only measures on-CPU time, wall-clock profiling samples all threads
#   regardless of their state (running, sleeping, waiting on I/O or locks).
#   This is especially valuable for diagnosing latency in web requests, as it
#   integrates with APM tracing to correlate spans with profiling data for
#   Endpoint Profiling.
#
# -----------------------------------------------------------------------------
# dd.profiling.ddprof.liveheap.enabled  (default: false)
#   Env: DD_PROFILING_DDPROF_LIVEHEAP_ENABLED
# -----------------------------------------------------------------------------
#   Activates the live-heap profiler engine. It samples heap allocations and
#   tracks which objects survived the most recent GC cycle, estimating the
#   number and size of live objects in the heap. Useful for identifying memory
#   leaks and understanding overall memory pressure.
#   Requires: dd-trace-java >= 1.39.0, JDK 11.0.23+, 17.0.11+, 21.0.3+, or
#   22+. Not available on Windows.
#
# -----------------------------------------------------------------------------
# dd.logs.injection  (default: true)   Env: DD_LOGS_INJECTION
# -----------------------------------------------------------------------------
#   Enables automatic injection of Datadog trace correlation IDs (dd.trace_id,
#   dd.span_id, dd.service, dd.env, dd.version) into the logging framework's
#   MDC (Mapped Diagnostic Context). This allows log entries to be correlated
#   with specific APM traces in the Datadog Logs UI, enabling seamless
#   navigation between Traces and Logs views.
#   Works with: Log4j2, Logback, SLF4J, java.util.logging.
#
# -----------------------------------------------------------------------------
# dd.service  (default: unnamed-java-app)   Env: DD_SERVICE
# -----------------------------------------------------------------------------
#   The name of the service. Part of Datadog's "Unified Service Tagging"
#   convention (dd.service + dd.env + dd.version). Appears as the top-level
#   service in the APM Service Catalog and is used to group traces, profiles,
#   and errors. These three tags are attached to every trace, profile, log, and
#   metric, enabling consistent filtering across all Datadog products.
#
# -----------------------------------------------------------------------------
# dd.env  (default: none)   Env: DD_ENV
# -----------------------------------------------------------------------------
#   The deployment environment (e.g. dev, staging, production). Used to
#   separate telemetry across environments in all Datadog views. Enables
#   environment-scoped filtering in APM, Logs, and Dashboards.
#
# -----------------------------------------------------------------------------
# dd.version  (default: none)   Env: DD_VERSION
# -----------------------------------------------------------------------------
#   The application version. Enables Deployment Tracking in Datadog APM, which
#   surfaces performance regressions, error-rate changes, and latency shifts
#   between deployments. Appears in traces, profiles, and the Service Catalog.
#
# -----------------------------------------------------------------------------
# dd.trace.sample.rate  (default: -1, defers to Datadog Agent)
#   Env: DD_TRACE_SAMPLE_RATE
# -----------------------------------------------------------------------------
#   Controls the percentage of traces sent from the tracer to the Datadog
#   backend. Value range: 0.0 (drop all) to 1.0 (keep all).
#   Setting 1.0 captures 100% of traces — appropriate for dev/staging but may
#   be too expensive in high-throughput production environments.
#   A default rate limit of 100 traces/sec applies when this is set.
#   When set to -1 (the default), the Agent-side sampling rules apply instead.
#
# -----------------------------------------------------------------------------
# dd.remote_config.enabled  (default: true)   Env: DD_REMOTE_CONFIG_ENABLED
# -----------------------------------------------------------------------------
#   Controls whether the tracer accepts Remote Configuration updates from the
#   Datadog Agent. When enabled, settings like sample rate and log injection
#   can be changed dynamically from the Datadog UI (Software Catalog) without
#   restarting the application.
#   Setting to false ensures the application only uses locally specified
#   configuration. Useful in dev or environments without a fully configured
#   Datadog Agent.
#
# -----------------------------------------------------------------------------
# dd.service.mapping  (default: none)   Env: DD_SERVICE_MAPPING
# -----------------------------------------------------------------------------
#   Renames downstream service names in traces via key:value pairs. Format is
#   "fromName:toName" (comma-separated for multiple mappings).
#   Here the auto-detected "h2" database service is renamed to
#   "my-h2-spring-petclinic-main-name-db" so it appears with a meaningful name
#   in the APM Service Map and dependency graphs rather than the generic "h2".
#
# -----------------------------------------------------------------------------
# dd.trace.health.metrics.enabled  (default: false)
#   Env: DD_TRACE_HEALTH_METRICS_ENABLED
# -----------------------------------------------------------------------------
#   Enables the tracer to emit internal health metrics about its own operation
#   (spans created, spans dropped, queue sizes, encoding time, etc.) via
#   DogStatsD (UDP port 8125 by default).
#   Useful for monitoring the health of the tracer itself and diagnosing
#   issues like dropped spans or agent connectivity problems.
#   Requires: DogStatsD enabled on the Datadog Agent. If the Agent runs in a
#   container, set DD_DOGSTATSD_NON_LOCAL_TRAFFIC=true and expose port 8125.
#
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Configurable paths — adjust these to match your environment
# ---------------------------------------------------------------------------

# Path to the Datadog Java agent JAR
DD_AGENT_JAR="/home/pwere/ddtracers/dd-java-agent.jar"

# Path to the application executable JAR produced by `mvn package`
APP_JAR="/home/pwere/dev2024/prj/jvp/spring-petclinic-main/target/spring-petclinic-4.0.0-SNAPSHOT.jar"

# Log directory used by Log4j2 (see log4j2.xml, property "log.dir")
LOG_DIR="${LOG_DIR:-/var/log/petclinic}"

# ---------------------------------------------------------------------------
# Launch the application
# ---------------------------------------------------------------------------

exec java \
  -javaagent:"${DD_AGENT_JAR}" \
  -Ddd.profiling.enabled=true \
  -Ddd.profiling.ddprof.enabled=true \
  -Ddd.profiling.ddprof.cpu.enabled=true \
  -Ddd.profiling.ddprof.wall.enabled=true \
  -Ddd.profiling.ddprof.liveheap.enabled=true \
  -Ddd.logs.injection=true \
  -Ddd.service=spring-petclinic-main \
  -Ddd.env=dev \
  -Ddd.version=4.0.0 \
  -Ddd.trace.sample.rate=1.0 \
  -Ddd.remote_config.enabled=false \
  -Ddd.service.mapping=h2:my-h2-spring-petclinic-main-name-db \
  -Ddd.trace.health.metrics.enabled=true \
  -Dlog.dir="${LOG_DIR}" \
  -jar "${APP_JAR}"
