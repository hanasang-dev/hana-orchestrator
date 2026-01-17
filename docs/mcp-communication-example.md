# MCP Communication Flow Example

## Scenario: Interface Layer → File Processor Layer

### 1. Interface Layer connects to File Processor Layer

**Interface Layer (Client) → File Processor Layer (Server):**
```json
{
  "jsonrpc": "2.0",
  "id": "conn-1",
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {
      "tools": {}
    },
    "clientInfo": {
      "name": "interface-layer",
      "version": "1.0.0"
    }
  }
}
```

**File Processor Layer Response:**
```json
{
  "jsonrpc": "2.0",
  "id": "conn-1",
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": {
      "tools": {}
    },
    "serverInfo": {
      "name": "file-processor",
      "version": "1.0.0"
    }
  }
}
```

### 2. Interface Layer discovers available tools

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": "tools-1",
  "method": "tools/list"
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": "tools-1",
  "result": {
    "tools": [
      {
        "name": "read_file",
        "description": "Read file contents",
        "inputSchema": {
          "type": "object",
          "properties": {
            "path": {"type": "string"}
          },
          "required": ["path"]
        }
      }
    ]
  }
}
```

### 3. Interface Layer calls a tool

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": "call-1",
  "method": "tools/call",
  "params": {
    "name": "read_file",
    "arguments": {
      "path": "/user/input.txt"
    }
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": "call-1",
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Hello, this is user input!"
      }
    ]
  }
}
```

## Key Points:
1. **JSON-RPC 2.0**: 모든 통신은 JSON-RPC 2.0 기반
2. **ID 기반**: 각 요청/응답은 고유 ID로 매칭
3. **메소드 기반**: 정해진 메소드들만 사용 가능
4. **에러 처리**: 에러도 JSON-RPC 형식으로 표준화