let s:p_dir = expand('<sfile>:p:h')
let g:is_running = 0
let g:socket_repl_plugin_ready = 0
let g:nvim_tcp_plugin_channel = 0
let g:eval_entire_ns_decl = 0 " 0 = SwitchBufferNS uses `in-ns`. 1 = SwitchBufferNS evals entire ns declaration

let s:not_ready = "SocketREPL plugin not ready (starting)"

function! StartIfNotRunning()
  if g:is_running == 0
    echo 'Starting SocketREPL plugin...'
    let jar_file_path = s:p_dir . '/../' . 'socket-repl-plugin-0.1.0-standalone.jar'
    call jobstart(['java', '-jar', jar_file_path], {'rpc': v:true})
    let g:is_running = 1
  endif
endfunction
command! Start call StartIfNotRunning()

function! Connect(host_colon_port, op_code)
  if a:host_colon_port == ""
    let conn = "localhost:5555"
  else
    let conn = a:host_colon_port
  endif
  let res = rpcnotify(g:nvim_tcp_plugin_channel, a:op_code, conn)
  return res
endfunction

function! ReadyConnect(host_colon_port, op_code)
  if g:socket_repl_plugin_ready == 1
    call Connect(a:host_colon_port, a:op_code)
  else
    echo s:not_ready
  endif
endfunction
command! -nargs=? Connect call ReadyConnect("<args>", "connect")
command! -nargs=? NConnect call ReadyConnect("<args>", "connect-nrepl")

function! EvalBuffer()
  ReplLog
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'eval-buffer', [])
  return res
endfunction

function! ReadyEvalBuffer()
  if g:socket_repl_plugin_ready == 1
    call EvalBuffer()
  else
    echo s:not_ready
  endif
endfunction
command! EvalBuffer call ReadyEvalBuffer()

function! EvalForm()
  ReplLog
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'eval-form', [])
  return res
endfunction

function! ReadyEvalForm()
  if g:socket_repl_plugin_ready == 1
    call EvalForm()
  else
    echo s:not_ready
  endif
endfunction
command! EvalForm call ReadyEvalForm()

function! Eval()
  ReplLog
  call inputsave()
  let form = input('=> ')
  call inputrestore()
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'eval', [form])
  return res
endfunction

function! ReadyEval()
  if g:socket_repl_plugin_ready == 1
    call Eval()
  else
    echo s:not_ready
  endif
endfunction
command! Eval call ReadyEval()

function! ReplLog(buffer_cmd)
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'show-log', a:buffer_cmd)
  return res
endfunction

function! ReadyReplLog(buffer_cmd)
  if g:socket_repl_plugin_ready == 1
    call ReplLog(a:buffer_cmd)
  else
    echo s:not_ready
  endif
endfunction
command! ReplLog call ReadyReplLog(':botright new')

function! DismissReplLog()
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'dismiss-log', [])
  return res
endfunction

function! ReadyDismissReplLog()
  if g:socket_repl_plugin_ready == 1
    call DismissReplLog()
  else
    echo s:not_ready
  endif
endfunction
command! DismissReplLog call ReadyDismissReplLog()

function! Doc()
  ReplLog
  call inputsave()
  let symbol = input('Doc for: ')
  call inputrestore()
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'doc', [symbol])
  return res
endfunction

function! ReadyDoc()
  if g:socket_repl_plugin_ready == 1
    call Doc()
  else
    echo s:not_ready
  endif
endfunction
command! Doc call ReadyDoc()

function! DocCursor()
  ReplLog
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'doc-cursor', [])
  return res
endfunction

function! ReadyCursorDoc()
  if g:socket_repl_plugin_ready == 1
    call DocCursor()
  else
    echo s:not_ready
  endif
endfunction
command! DocCursor call ReadyCursorDoc()

function! Source()
  ReplLog
  call inputsave()
  let symbol = input('Source for: ')
  call inputrestore()
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'source', [symbol])
  return res
endfunction

function! ReadySource()
  if g:socket_repl_plugin_ready == 1
    call Source()
  else
    echo s:not_ready
  endif
endfunction
command! Source call ReadySource()

function! SourceCursor()
  ReplLog
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'source-cursor', [])
  return res
endfunction

function! ReadyCursorSource()
  if g:socket_repl_plugin_ready == 1
    call SourceCursor()
  else
    echo s:not_ready
  endif
endfunction
command! SourceCursor call ReadyCursorSource()

function! SwitchBufferNS()
  call rpcnotify(g:nvim_tcp_plugin_channel, 'switch-buffer-ns', [])
endfunction

function! ReadySwitchBufferNS()
  if g:socket_repl_plugin_ready == 1
    call SwitchBufferNS()
  else
    echo s:not_ready
  endif
endfunction
command! SwitchBufferNS call ReadySwitchBufferNS()

if !exists('g:disable_socket_repl_mappings')
  nnoremap K :DocCursor<cr>
  nnoremap [d :SourceCursor<cr>
  nnoremap <leader>e :Eval<cr>
  nnoremap <leader>eb :EvalBuffer<cr>
  nnoremap <leader>ef :EvalForm<cr>
  nnoremap <leader>rlog :ReplLog<cr>
  nnoremap <leader>drlog :DismissReplLog<cr>
endif

if !exists('g:manually_start_socket_repl_plugin')
  if has("nvim")
    call StartIfNotRunning()
  endif
endif
