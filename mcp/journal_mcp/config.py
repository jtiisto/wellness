"""Configuration for Journal MCP Server."""

from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional


@dataclass
class MCPConfig:
    """Configuration for the Journal MCP server."""

    db_path: Path
    max_rows: int = 1000
    max_rows_absolute: int = 5000
    enable_query_logging: bool = False
    strict_validation: bool = True
    transport: str = "stdio"
    host: str = "127.0.0.1"
    port: int = 8000

    @classmethod
    def from_db_path(
        cls,
        db_path: Path,
        max_rows: int = 1000,
        enable_query_logging: bool = False,
    ) -> "MCPConfig":
        """Create configuration from database path."""
        return cls(
            db_path=db_path,
            max_rows=max_rows,
            enable_query_logging=enable_query_logging,
        )

    def validate(self) -> None:
        """Validate configuration settings."""
        if not self.db_path.exists():
            raise ValueError(f"Database file not found: {self.db_path}")

        if not self.db_path.is_file():
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
