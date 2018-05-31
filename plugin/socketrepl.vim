let s:p_dir = expand('<sfile>:p:h')
let g:is_running = 0
let g:socket_repl_plugin_ready = 0
let g:nvim_tcp_plugin_channel = 0
let g:eval_entire_ns_decl = 1 " 0 = SwitchBufferNS uses `in-ns`. 1 = SwitchBufferNS evals entire ns declaration
let g:socket_repl_injected = 0

let s:not_ready = "SocketREPL plugin not ready (starting)"

function! socketrepl#omnicomplete(findstart, base)
  if a:findstart
    let res = rpcrequest(g:nvim_tcp_plugin_channel, 'complete-initial', [])
    return l:res
  else
    echo a:base
    let res = rpcrequest(g:nvim_tcp_plugin_channel, 'complete-matches', a:base)
    return l:res
  endif
endfunction

function! s:StartIfNotRunning()
  if g:is_running == 0
    echo 'Starting SocketREPL plugin...'
    let jar_file_path = s:p_dir . '/../' . 'socket-repl-plugin-0.1.0-standalone.jar'
    call jobstart(['java', '-jar', jar_file_path], {'rpc': v:true})
    let g:is_running = 1
  endif
endfunction
command! Start call s:StartIfNotRunning()

function! s:Connect(host_colon_port, op_code)
  if a:host_colon_port == ""
    let conn = "localhost:5555"
  else
    let conn = a:host_colon_port
  endif
  let res = rpcrequest(g:nvim_tcp_plugin_channel, a:op_code, conn)

  if l:res == "success"
    call rpcnotify(g:nvim_tcp_plugin_channel, 'inject', '')
  endif
  return res
endfunction

function! s:ReadyConnect(host_colon_port, op_code)
  if g:socket_repl_plugin_ready == 1
    call s:Connect(a:host_colon_port, a:op_code)
  else
    echo s:not_ready
  endif
endfunction
command! -nargs=? Connect call s:ReadyConnect("<args>", "connect")
command! -nargs=? NConnect call s:ReadyConnect("<args>", "connect-nrepl")

function! s:EvalBuffer()
  ReplLog
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'eval-buffer', [])
  return res
endfunction

function! s:ReadyEvalBuffer()
  if g:socket_repl_plugin_ready == 1
    call s:EvalBuffer()
  else
    echo s:not_ready
  endif
endfunction
command! EvalBuffer call s:ReadyEvalBuffer()

function! s:EvalForm()
  ReplLog
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'eval-form', [])
  return res
endfunction

function! s:ReadyEvalForm()
  if g:socket_repl_plugin_ready == 1
    call s:EvalForm()
  else
    echo s:not_ready
  endif
endfunction
command! EvalForm call s:ReadyEvalForm()

" Thanks vim-fireplace!
function! socketrepl#eval_complete(A, L, P) abort
  let prefix = matchstr(a:A, '\%(.* \|^\)\%(#\=[\[{('']\)*')
  let keyword = a:A[strlen(prefix) : -1]
  return sort(map(socketrepl#omnicomplete(0, keyword), 'prefix . v:val.word'))
endfunction

function! s:Eval()
  ReplLog
  call inputsave()
  let form = input('=> ', '', 'customlist,socketrepl#eval_complete')
  call inputrestore()
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'eval', [form])
  return res
endfunction

function! s:ReadyEval()
  if g:socket_repl_plugin_ready == 1
    call s:Eval()
  else
    echo s:not_ready
  endif
endfunction
command! Eval call s:ReadyEval()

function! s:ReplLog(buffer_cmd)
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'show-log', a:buffer_cmd)
  return res
endfunction

function! s:ReadyReplLog(buffer_cmd)
  if g:socket_repl_plugin_ready == 1
    call s:ReplLog(a:buffer_cmd)
  else
    echo s:not_ready
  endif
endfunction
command! ReplLog call s:ReadyReplLog(':botright new')

function! s:DismissReplLog()
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'dismiss-log', [])
  return res
endfunction

function! s:ReadyDismissReplLog()
  if g:socket_repl_plugin_ready == 1
    call s:DismissReplLog()
  else
    echo s:not_ready
  endif
endfunction
command! DismissReplLog call s:ReadyDismissReplLog()

function! s:Doc(symbol)
  ReplLog
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'doc', [a:symbol])
  return res
endfunction

function! s:ReadyDoc(symbol)
  if g:socket_repl_plugin_ready == 1
    call s:Doc(a:symbol)
  else
    echo s:not_ready
  endif
endfunction
command! -bar -nargs=1 -complete=customlist,socketrepl#eval_complete Doc :exe s:ReadyDoc(<q-args>)

function! s:DocCursor()
  ReplLog
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'doc-cursor', [])
  return res
endfunction

function! s:ReadyCursorDoc()
  if g:socket_repl_plugin_ready == 1
    call s:DocCursor()
  else
    echo s:not_ready
  endif
endfunction
command! DocCursor call s:ReadyCursorDoc()

function! s:Source(symbol)
  ReplLog
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'source', [a:symbol])
  return res
endfunction

function! s:ReadySource(symbol)
  if g:socket_repl_plugin_ready == 1
    call s:Source(a:symbol)
  else
    echo s:not_ready
  endif
endfunction
command! Source call s:ReadySource()
command! -bar -nargs=1 -complete=customlist,socketrepl#eval_complete Source :exe s:ReadySource(<q-args>)

function! s:SourceCursor()
  ReplLog
  let res = rpcnotify(g:nvim_tcp_plugin_channel, 'source-cursor', [])
  return res
endfunction

function! s:ReadyCursorSource()
  if g:socket_repl_plugin_ready == 1
    call s:SourceCursor()
  else
    echo s:not_ready
  endif
endfunction
command! SourceCursor call s:ReadyCursorSource()

function! s:SwitchBufferNS()
  call rpcnotify(g:nvim_tcp_plugin_channel, 'switch-buffer-ns', [])
endfunction

function! s:ReadySwitchBufferNS()
  if g:socket_repl_plugin_ready == 1
    call s:SwitchBufferNS()
  else
    echo s:not_ready
  endif
endfunction
command! SwitchBufferNS call s:ReadySwitchBufferNS()

augroup socketrepl_completion
  autocmd!
  autocmd FileType clojure setlocal omnifunc=socketrepl#omnicomplete
augroup END

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
    call s:StartIfNotRunning()
  endif
endif
