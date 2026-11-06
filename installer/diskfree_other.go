//go:build !windows

package main

import "syscall"

// diskFreeMB: место, доступное обычному пользователю (statfs), в МБ; -1, если не узнать
func diskFreeMB(path string) int64 {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return -1
	}
	return int64(uint64(st.Bavail) * uint64(st.Bsize) >> 20)
}
