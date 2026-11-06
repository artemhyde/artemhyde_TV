//go:build windows

package main

import (
	"syscall"
	"unsafe"
)

var procFree = syscall.NewLazyDLL("kernel32.dll").NewProc("GetDiskFreeSpaceExW")

// diskFreeMB: место, доступное этому пользователю (GetDiskFreeSpaceExW), в МБ; -1, если не узнать
func diskFreeMB(path string) int64 {
	p, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return -1
	}
	var avail, total, free uint64
	r, _, _ := procFree.Call(uintptr(unsafe.Pointer(p)), uintptr(unsafe.Pointer(&avail)), uintptr(unsafe.Pointer(&total)), uintptr(unsafe.Pointer(&free)))
	if r == 0 {
		return -1
	}
	return int64(avail >> 20)
}
