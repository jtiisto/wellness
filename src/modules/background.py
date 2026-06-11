"""Fire-and-forget background tasks with strong references.

asyncio's event loop holds only weak references to tasks: a task created with
``asyncio.create_task(...)`` whose result is discarded can be garbage-collected
mid-execution, silently killing the work (an analysis report frozen in
'running', a workout hook row stuck at exit_code NULL). ``spawn`` keeps each
task in a module-level set until it completes, which is the documented
pattern from the asyncio manual.
"""
import asyncio

_tasks: set = set()


def spawn(coro) -> asyncio.Task:
    """Create a background task that cannot be garbage-collected mid-flight.

    The task is held in a strong-reference set and discards itself on
    completion. Exceptions are the coroutine's responsibility (all current
    callers catch and persist their own failures); the done-callback only
    drops the reference.
    """
    task = asyncio.get_event_loop().create_task(coro)
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)
    return task
