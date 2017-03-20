# Neovim Socket Repl Plugin

A [Neovim](https://github.com/neovim/neovim) plugin for Clojure which uses the built in [socket repl](http://clojure.org/reference/repl_and_main#_launching_a_socket_server) introduced in version 1.8 of Clojure.

This plugin uses the [Neovim Clojure plugin host](https://github.com/jebberjeb/neovim-client). While intended to be a demonstration of the plugin host library, it may also be suitable for daily Clojure development.

Simply, this plugin uses a socket connection to send code to the repl. Nothing (very little) more. All (most) of the plugin code is written using Clojure. There's no (some) VimScript to sift through. This plugin is probably a bit too transparent. You see absolutely everything that's sent to the repl. If you eval an entire buffer...

## Usage

Start a Clojure program with a socket repl server. This can be done by
adding the following JVM options to any Clojure application.

```
-Dclojure.server.repl="{:port 5555 :accept clojure.core.server/repl}"
```

Connect to a socket repl. This connection is shared by all Neovim buffers.
Optionally, provide a `host:port` argument. Default is `localhost:5555`.

```
:Connect
```

Eval any buffer or form under cursor.

```
<leader>eb
```

or

```
<leader>ef
```

The results of all repl activity are logged, and displayed in a buffer. To
dismiss that log buffer, use `q` from within the log buffer, or
`<leader>drlog` from any buffer.

After dismissing the log, it may be resurrected.

```
<leader>rlog
```

Docs for the expression under the cursor are available.

```
<leader>doc
```

Note that the above leader-based mappings can be disabled by setting
`g:disable_socket_repl_mappings` to `1` in your .vimrc. The following commands
are available to create your own mappings:

```
:Connect
:EvalBuffer
:EvalForm
:Doc
:ReplLog
:DismissReplLog
```

## Installation

Install however you normally do it. For example, using Vundle you'd add the
following line to your `.vimrc`:

```
Plugin 'jebberjeb/clojure-socketrepl.nvim'
```

Then (after sourcing `.vimrc`), from neovim:

```
:VundleInstall
```

## Dependencies

This plugin requires a version of the Java version 1.6 or higher. You've probably already got this if you're using Clojure.

## Plugin Architecture

![Architecture](/doc/clojure-socketrepl-nvim.png)

## Developing

### Tmux

The `tmux-run-dev.sh` script launches Neovim, starts the plugin process and
a repl, as well as a second Clojure process running a socket repl server. After
using this script, simply run the `:Connect` command from Neovim to connect
to the socket repl server.

### Without Tmux

Start Neovim sourcing the debug plugin script. Make it listen for plugin
connections using a socket on port 7777.

```
NVIM_LISTEN_ADDRESS=127.0.0.1:7777 nvim -S plugin/socketrepl.vim.debug
```

Start the plugin. Then you'll need to connect to Neovim from the repl.

```
$> lein repl
$> (go)
```

You can now use plugin commands from within Neovim `:Connect`, `:EvalBuffer`,
etc.

Note that you'll probably want to rely heavily on the asynchronous
neovim-client functions when you want (the plugin) to make a request
of neovim. This is because the Neovim function `rpcrequest` blocks until
it has received a response (from your plugin). Using async on the plugin
side is the easiest way to avoid deadlock.

## Changes

### 0.1.0

* Use `tools.reader` for parsing source to eval
* Fix bug namespaced keys using `::kw` form were expanded using the wrong
namespace when read by the plugin's reader
