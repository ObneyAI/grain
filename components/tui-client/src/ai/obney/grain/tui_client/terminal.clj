(ns ai.obney.grain.tui-client.terminal
  "JLine terminal setup for the thin client. Reuses the tui-adapter
   stdio helpers — opening the terminal, raw mode, alt-screen toggle,
   and the input pump that parses bytes into key events.

   This namespace is a thin re-export so the client's main loop reads
   cleanly; behavior is identical to `tui-adapter.transport.stdio`."
  (:require [ai.obney.grain.tui-adapter.transport.stdio :as stdio]))

(def open-terminal       stdio/open-terminal)
(def make-output-sink    stdio/make-output-sink)
(def enter-tui!          stdio/enter-tui!)
(def leave-tui!          stdio/leave-tui!)
(def start-input-pump!   stdio/start-input-pump!)
(def install-winch-handler! stdio/install-winch-handler!)
