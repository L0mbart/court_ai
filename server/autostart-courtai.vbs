Set WshShell = CreateObject("WScript.Shell")
WshShell.CurrentDirectory = "D:\Efer Kano\Documents\OneDrive\Gawean\Proyek\Basket Ball APPS\server"
WshShell.Run """D:\Python\Python312\python.exe"" generate_certs.py", 0, True
WshShell.Run """D:\Python\Python312\python.exe"" -m uvicorn main:app --host 0.0.0.0 --port 8080", 0, False
WshShell.Run """D:\Python\Python312\python.exe"" -m uvicorn main:app --host 0.0.0.0 --port 8443 --ssl-certfile certs\cert.pem --ssl-keyfile certs\key.pem", 0, False
