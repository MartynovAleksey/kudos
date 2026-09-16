# Design: Kyuubi engine logs in Ozone

Every Kyuubi engine is a separate JVM. Kyuubi redirects its stdout and stderr into
`$KYUUBI_HOME/work/<user>/kyuubi-spark-sql-engine.log.<n>`, a file that lives only inside the
`kyuubi` container. This component ships that stream to Ozone and shows it back on the Logs tab
of a Spark job.

## Pipeline

```
kyuubi                     log-collector                 ozone
work/<user>/               Fluent Bit                    S3 Gateway :9878
  kyuubi-spark-sql   ──▶   tail  ──▶  s3 output  ──▶     /s3v/enginelogs/<user>/<seq>/part-<n>.log
  -engine.log.<n>          (log_key log)

app                        reads back over ofs://, concatenates, serves as text/plain
  /jobs/{applicationId}/engine-log
```

The collector holds the `kyuubi-work` volume read-only, so it tails the same files the engines
write without running inside the Kyuubi container. It authenticates to the S3 Gateway with the
secret Ozone mints at startup and publishes to `/shared/s3-credentials.env`; no keytab is
involved.

## Why the objects are raw text

The `s3` output is configured with `log_key log`, which writes only the value of that key
instead of a JSON envelope. The `tail` input puts the untouched line in `log`, so an object
holds exactly the bytes the engine wrote. Two settings follow from that:

* no multiline parser — folding a stack trace into one record would reformat the lines;
* `Skip_Empty_Lines Off` — blank lines inside a stack trace are part of the output.

Compression is left off so the Ozone screen's preview pane renders the objects as text.

## Object layout

```
/s3v/enginelogs/<user>/<seq>/part-<index>.log
```

`<user>` and `<seq>` come from the engine log file name via `Tag_Regex`; `<index>` is Fluent
Bit's `$INDEX` counter, persisted in the collector's volume. The counter is shared across every
tag, so one engine's objects can be `part-0`, `part-2`, `part-5` — gaps are normal. What matters
is that the numbers increase in write order, so sorting them numerically restores the original
file. `cat part-*.log` in numeric order equals the engine's log.

## Resolving an application to its log

Nothing in the object names carries the Spark application id. Instead the application is matched
to its directory by the line Spark prints when it opens its event log:

```
SingleEventLogFileWriter: Logging events to ofs://ozone.test.local/spark/eventlogs/<applicationId>.inprogress
```

The writer class differs between Spark versions, so the match is on the `eventlogs/<applicationId>`
substring rather than on the class name.

`EngineLogService` looks up the application's owner from the History Server, scans that user's
directories for the marker, and caches the resolved directory. Only the lookup is cached — a
running engine keeps appending, so the content is re-read on every request.

This needs no change to Spark or Kyuubi, and it works for finished and running applications
alike. It does depend on a Spark log line rather than a contract: if the format changes, the
Logs tab goes empty and nothing else is affected.

## Access

Ozone ACLs are per-bucket and the application holds no service credentials — it reads `ofs://`
with the signed-in user's ticket. So `/s3v/enginelogs` grants read to every authenticated user
(`world::rl`, also in `DEFAULT` scope so new keys inherit it) and ownership is enforced by the
application: `/jobs/{applicationId}/engine-log` answers 403 unless
`SparkApplicationAccessService.canView` passes.

The deliberate consequence is that the Ozone file browser, which is a general `ofs://` browser,
can reach another user's engine-log directory. Closing that would mean one bucket per user, with
one collector output per user.
