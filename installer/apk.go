package main

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
)

// Подпись APK по схемам v2 и v3 (блок «APK Sig Block 42» перед центральным каталогом ZIP): SHA-256 первого
// сертификата подписанта, то же число, что печатает apksigner --print-certs. Без Java: человеку она не нужна.
// Схема v1 (только META-INF) не читается: такой файл установщик не ставит
// certOf: через неё сертификат смотрят установка и скачивание (проверки подставляют свою)
var certOf = apkCertSHA256

func apkCertSHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return "", err
	}
	size := st.Size()
	if size < 22 {
		return "", errors.New("не ZIP")
	}
	tail := int64(65557)
	if tail > size {
		tail = size
	}
	buf := make([]byte, tail)
	if _, err := f.ReadAt(buf, size-tail); err != nil {
		return "", err
	}
	eocd := -1
	for i := len(buf) - 22; i >= 0; i-- {
		if binary.LittleEndian.Uint32(buf[i:]) == 0x06054b50 {
			eocd = i
			break
		}
	}
	if eocd < 0 {
		return "", errors.New("не ZIP: нет конца каталога")
	}
	cd := int64(binary.LittleEndian.Uint32(buf[eocd+16:]))
	if cd < 32 || cd > size {
		return "", errors.New("испорченный ZIP")
	}
	footer := make([]byte, 24)
	if _, err := f.ReadAt(footer, cd-24); err != nil {
		return "", err
	}
	if string(footer[8:]) != "APK Sig Block 42" {
		return "", errors.New("нет подписи схемы v2 или v3")
	}
	bsize := int64(binary.LittleEndian.Uint64(footer[:8]))
	if bsize < 24 || bsize > cd-8 {
		return "", errors.New("испорченный блок подписи")
	}
	block := make([]byte, bsize-24)
	if _, err := f.ReadAt(block, cd-bsize); err != nil {
		return "", err
	}
	var v2, v3 []byte
	for p := 0; p+12 <= len(block); {
		l := int(binary.LittleEndian.Uint64(block[p:]))
		if l < 4 || p+8+l > len(block) {
			return "", errors.New("испорченная пара в блоке подписи")
		}
		id := binary.LittleEndian.Uint32(block[p+8:])
		val := block[p+12 : p+8+l]
		switch id {
		case 0x7109871a:
			v2 = val
		case 0xf05368c0:
			v3 = val
		}
		p += 8 + l
	}
	scheme := v3
	if scheme == nil {
		scheme = v2
	}
	if scheme == nil {
		return "", errors.New("в блоке нет подписи v2 или v3")
	}
	// последовательность подписантов → первый → подписанные данные → дайджесты, затем сертификаты → первый
	cert, err := walkLP(scheme, 0, 0, 0, 1, 0)
	if err != nil {
		return "", fmt.Errorf("не прочитал сертификат: %v", err)
	}
	sum := sha256.Sum256(cert)
	return hex.EncodeToString(sum[:]), nil
}

// walkLP: путь по вложенным полям «длина uint32 + значение». Число в пути: сколько полей пропустить на этом уровне,
// потом войти в следующее. Подписант: [подписанные данные]; подписанные данные: [дайджесты, сертификаты]
func walkLP(b []byte, skips ...int) ([]byte, error) {
	cur := b
	for _, s := range skips {
		for i := 0; i <= s; i++ {
			if len(cur) < 4 {
				return nil, errors.New("короткое поле")
			}
			l := int(binary.LittleEndian.Uint32(cur))
			if 4+l > len(cur) {
				return nil, errors.New("поле длиннее блока")
			}
			if i == s {
				cur = cur[4 : 4+l]
				break
			}
			cur = cur[4+l:]
		}
	}
	return cur, nil
}
