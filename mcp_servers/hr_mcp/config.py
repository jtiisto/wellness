"""Configuration for the HR MCP Server."""

from dataclasses import dataclass
from pathlib import Path


@dataclass
class MCPConfig:
    """Configuration for read-only HR analysis access.

    `port` is only consulted by the http/sse transports; the server runs over
    stdio like the other two. 8004 keeps it clear of coach (8002) and
    journal (8000).
    """

    db_path: Path
    max_rows: int = 1000
    max_rows_absolute: int = 5000
    transport: str = "stdio"
    host: str = "127.0.0.1"
    port: int = 8004

    @classmethod
    def from_db_path(cls, db_path: Path, max_rows: int = 1000) -> "MCPConfig":
        """Create configuration from database path."""
        return cls(db_path=Path(db_path), max_rows=max_rows)

    def validate(self) -> None:
        """Validate configuration settings.

        DELIBERATE DEVIATION from coach/journal: a missing `hr.db` is NOT a
        configuration error. Those modules' databases exist from the server's
        first start; HR history starts empty by design (fresh-start migration
        decision) and the file only appears when the first capture batch is
        accepted. Refusing to start would leave the tools unusable — and
        unlistable — on a machine that simply has not captured anything yet, so
        absence is reported per call instead, by the same `HrDataUnavailable`
        message the CLI prints. An existing path that is not a file stays a
        hard error: that one is a misconfiguration, not an empty history.
        """
        if self.db_path.exists() and not self.db_path.is_file():
            raise ValueError(f"Database path is not a file: {self.db_path}")

        if self.max_rows < 1:
            raise ValueError("max_rows must be at least 1")

        if self.max_rows > self.max_rows_absolute:
            raise ValueError(
                f"max_rows ({self.max_rows}) cannot exceed max_rows_absolute ({self.max_rows_absolute})"
            )

        if self.transport not in ("stdio", "http", "sse"):
            raise ValueError(f"Invalid transport: {self.transport}")

        if self.port < 1 or self.port > 65535:
            raise ValueError(f"Invalid port: {self.port}")
