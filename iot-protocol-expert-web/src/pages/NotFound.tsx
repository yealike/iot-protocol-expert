import { Result } from 'antd'
import { Link } from 'react-router-dom'
import { Button } from 'antd'

export default function NotFound({ path }: { path: string }) {
  return (
    <Result
      status="404"
      title="页面不存在"
      subTitle={path}
      extra={
        <Link to="/overview">
          <Button type="primary">回到总览</Button>
        </Link>
      }
    />
  )
}
